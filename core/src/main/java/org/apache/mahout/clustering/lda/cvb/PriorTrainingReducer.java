package org.apache.mahout.clustering.lda.cvb;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.lib.MultipleOutputs;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.mahout.math.DenseMatrix;
import org.apache.mahout.math.DenseVector;
import org.apache.mahout.math.Matrix;
import org.apache.mahout.math.MatrixSlice;
import org.apache.mahout.math.SparseRowMatrix;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;

public class PriorTrainingReducer extends MapReduceBase
    implements Reducer<IntWritable, VectorWritable, IntWritable, VectorWritable> {
  private static final Logger log = LoggerFactory.getLogger(PriorTrainingReducer.class);

  public enum Counters {
    DOCS,
    SKIPPED_DOC_IDS,
    UNUSED_PRIORS,
    USED_DOCS,
    DOCS_WITH_PRIORS,
    NUM_NONZERO_MODEL_ENTRIES
  }

  public static final String DOC_TOPICS = "docTopics";
  public static final String TOPIC_TERMS = "topicTerms";
  private ModelTrainer modelTrainer;
  private int maxIters;
  private int numTopics;
  private boolean onlyLabeledDocs;
  private boolean useSparseModel;
  private MultipleOutputs multipleOutputs;
  private Reporter reporter;

  protected ModelTrainer getModelTrainer() {
    return modelTrainer;
  }

  protected int getMaxIters() {
    return maxIters;
  }

  protected int getNumTopics() {
    return numTopics;
  }

  @Override
  public void configure(JobConf conf) {
    try {
      log.info("Retrieving configuration");
      multipleOutputs = new MultipleOutputs(conf);
      double eta = conf.getFloat(CVB0Driver.TERM_TOPIC_SMOOTHING, Float.NaN);
      double alpha = conf.getFloat(CVB0Driver.DOC_TOPIC_SMOOTHING, Float.NaN);
      numTopics = conf.getInt(CVB0Driver.NUM_TOPICS, -1);
      int numTerms = conf.getInt(CVB0Driver.NUM_TERMS, -1);
      int numUpdateThreads = conf.getInt(CVB0Driver.NUM_UPDATE_THREADS, 1);
      int numTrainThreads = conf.getInt(CVB0Driver.NUM_TRAIN_THREADS, 4);
      maxIters = conf.getInt(CVB0Driver.MAX_ITERATIONS_PER_DOC, 10);
      double modelWeight = conf.getFloat(CVB0Driver.MODEL_WEIGHT, 1f);
      onlyLabeledDocs = conf.getBoolean(CVB0Driver.ONLY_LABELED_DOCS, false);
      useSparseModel = conf.getBoolean(CVB0Driver.USE_SPARSE_MODEL, false);

      log.info("Initializing read model");
      TopicModel readModel;
      Path[] modelPaths = CVB0Driver.getModelPaths(conf);
      if(modelPaths != null && modelPaths.length > 0) {
        readModel = new TopicModel(conf, eta, alpha, null, numUpdateThreads, modelWeight, modelPaths);
      } else {
        // TODO check whether this works: allowing the counts to be *zero* and just using smoothing
        log.info("No model files found, starting with uniform p(term|topic) prior");
        Matrix m = useSparseModel ? new SparseRowMatrix(numTopics, numTerms, true) : new DenseMatrix(numTopics, numTerms);
        // m.assign(1.0 / numTerms);
        readModel = new TopicModel(m, eta, alpha, null, numTrainThreads, modelWeight);
      }

      log.info("Initializing write model");
      TopicModel writeModel = modelWeight == 1
          ? new TopicModel(useSparseModel
                           ? new SparseRowMatrix(numTopics, numTerms)
                           : new DenseMatrix(numTopics, numTerms),
                           eta, alpha, null, numUpdateThreads, 1.0)
          : readModel;

      log.info("Initializing model trainer");
      modelTrainer = new ModelTrainer(readModel, writeModel, numTrainThreads, numTopics, numTerms);
      modelTrainer.start();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void reduce(IntWritable docId, Iterator<VectorWritable> vectors,
      OutputCollector<IntWritable,VectorWritable> out, Reporter reporter)
      throws IOException {
    if(this.reporter == null) {
      this.reporter = reporter;
    }
    Counter docCounter = reporter.getCounter(Counters.DOCS);
    docCounter.increment(1);
    Vector topicVector = null;
    Vector document = null;
    while(vectors.hasNext()) {
      VectorWritable v = vectors.next();
      if(v.get().size() == numTopics) {
        topicVector = v.get();
      } else {
        document = v.get();
      }
    }
    if(document == null) {
      if(topicVector != null) {
        reporter.getCounter(Counters.UNUSED_PRIORS).increment(1);
      }
      reporter.getCounter(Counters.SKIPPED_DOC_IDS).increment(1);
      return;
    } else if(topicVector == null && onlyLabeledDocs) {
      reporter.getCounter(Counters.SKIPPED_DOC_IDS).increment(1);
      return;
    } else {
      if(topicVector == null) {
        topicVector = new DenseVector(numTopics).assign(1.0 / numTopics);
      } else {
        if(reporter.getCounter(Counters.DOCS_WITH_PRIORS).getCounter() % 100 == 0) {
          long docsWithPriors = reporter.getCounter(Counters.DOCS_WITH_PRIORS).getCounter();
          long skippedDocs = reporter.getCounter(Counters.SKIPPED_DOC_IDS).getCounter();
          long total = reporter.getCounter(Counters.DOCS).getCounter();
          log.info("Processed {} docs total, {} with priors, skipped {} docs",
              new Object[]{total, docsWithPriors, skippedDocs});
        }
        reporter.getCounter(Counters.DOCS_WITH_PRIORS).increment(1);
      }
      modelTrainer.trainSync(document, topicVector, true, 1);
      multipleOutputs.getCollector(DOC_TOPICS, reporter)
                     .collect(docId, new VectorWritable(topicVector));
      reporter.getCounter(Counters.USED_DOCS).increment(1);
    }
  }

  @Override
  public void close() throws IOException {
    log.info("Stopping model trainer");
    modelTrainer.stop();

    log.info("Writing model");
    TopicModel model = modelTrainer.getReadModel();
    int numNonZero = 0;
    for(MatrixSlice topic : model) {
      numNonZero += topic.vector().getNumNondefaultElements();
      multipleOutputs.getCollector(TOPIC_TERMS, reporter)
                     .collect(new IntWritable(topic.index()), new VectorWritable(topic.vector()));
    }
    reporter.getCounter(Counters.NUM_NONZERO_MODEL_ENTRIES).increment(numNonZero);
    multipleOutputs.close();
  }
}