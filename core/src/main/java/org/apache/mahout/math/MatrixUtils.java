package org.apache.mahout.math;

import com.google.common.collect.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Writable;
import org.apache.mahout.common.Pair;
import org.apache.mahout.common.iterator.sequencefile.SequenceFileIterable;
import org.apache.mahout.math.map.OpenObjectIntHashMap;

import java.io.IOException;
import java.util.List;

public class MatrixUtils {

  public static void write(Path outputDir, Configuration conf, VectorIterable matrix)
      throws IOException {
    FileSystem fs = outputDir.getFileSystem(conf);
    SequenceFile.Writer writer = SequenceFile.createWriter(fs, conf, outputDir,
        IntWritable.class, VectorWritable.class);
    IntWritable topic = new IntWritable();
    VectorWritable vector = new VectorWritable();
    for(MatrixSlice slice : matrix) {
      topic.set(slice.index());
      vector.set(slice.vector());
      writer.append(topic, vector);
    }
    writer.close();
  }

  public static Matrix read(Configuration conf, Path... modelPaths) throws IOException {
    int numRows = -1;
    int numCols = -1;
    boolean sparse = false;
    List<Pair<Integer, Vector>> rows = Lists.newArrayList();
    for(Path modelPath : modelPaths) {
      for(Pair<IntWritable, VectorWritable> row :
          new SequenceFileIterable<IntWritable, VectorWritable>(modelPath, true, conf)) {
        rows.add(Pair.of(row.getFirst().get(), row.getSecond().get()));
        numRows = Math.max(numRows, row.getFirst().get());
        sparse = !row.getSecond().get().isDense();
        if(numCols < 0) {
          numCols = row.getSecond().get().size();
        }
      }
    }
    if(rows.isEmpty()) {
      throw new IOException(modelPaths + " have no vectors in it");
    }
    numRows++;
    Vector[] arrayOfRows = new Vector[numRows];
    for(Pair<Integer, Vector> pair : rows) {
      arrayOfRows[pair.getFirst()] = pair.getSecond();
    }
    Matrix matrix;
    if(sparse) {
      matrix = new SparseRowMatrix(new int[] {numRows, numCols}, arrayOfRows);
    } else {
      matrix = new DenseMatrix(numRows, numCols);
      for(int i = 0; i < numRows; i++) {
        matrix.assignRow(i, arrayOfRows[i]);
      }
    }
    return matrix;
  }

  public static OpenObjectIntHashMap<String> readDictionary(Configuration conf, Path... dictPath)
    throws IOException {
    OpenObjectIntHashMap<String> dictionary = new OpenObjectIntHashMap<String>();
    for(Path dictionaryFile : dictPath) {
      for (Pair<Writable, IntWritable> record
              : new SequenceFileIterable<Writable, IntWritable>(dictionaryFile, true, conf)) {
        dictionary.put(record.getFirst().toString(), record.getSecond().get());
      }
    }
    return dictionary;
  }

  public static String[] invertDictionary(OpenObjectIntHashMap<String> termIdMap) {
    int maxTermId = -1;
    for(String term : termIdMap.keys()) {
      maxTermId = Math.max(maxTermId, termIdMap.get(term));
    }
    maxTermId++;
    String[] dictionary = new String[maxTermId];
    for(String term : termIdMap.keys()) {
      dictionary[termIdMap.get(term)] = term;
    }
    return dictionary;
  }

}
