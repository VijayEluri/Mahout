/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.math.hadoop.decomposer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.mahout.common.MahoutTestCase;
import org.apache.mahout.common.iterator.sequencefile.SequenceFileValueIterable;
import org.apache.mahout.math.DenseMatrix;
import org.apache.mahout.math.Matrix;
import org.apache.mahout.math.NamedVector;
import org.apache.mahout.math.Vector;
import org.apache.mahout.math.VectorWritable;
import org.apache.mahout.math.hadoop.DistributedRowMatrix;
import org.apache.mahout.math.hadoop.TestDistributedRowMatrix;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TestDistributedLanczosSolverCLI extends MahoutTestCase {
  private static final Logger log = LoggerFactory.getLogger(TestDistributedLanczosSolverCLI.class);

  @Test
  public void testDistributedLanczosSolverCLI() throws Exception {
    Path testData = getTestTempDirPath("testdata");
    DistributedRowMatrix corpus =
        new TestDistributedRowMatrix().randomDistributedMatrix(500, 450, 500, 10, 10.0, true, testData.toString());
    corpus.setConf(new Configuration());
    Path output = getTestTempDirPath("output");
    Path tmp = getTestTempDirPath("tmp");
    Path workingDir = getTestTempDirPath("working");
    String[] args = {
        "-i", new Path(testData, "distMatrix").toString(),
        "-o", output.toString(),
        "--tempDir", tmp.toString(),
        "--numRows", "500",
        "--numCols", "500",
        "--rank", "5",
        "--symmetric", "true",
        "--workingDir", workingDir.toString()
    };
    new DistributedLanczosSolver().new DistributedLanczosSolverJob().run(args);

    output = getTestTempDirPath("output2");
    tmp = getTestTempDirPath("tmp2");
    args = new String[] {
        "-i", new Path(testData, "distMatrix").toString(),
        "-o", output.toString(),
        "--tempDir", tmp.toString(),
        "--numRows", "500",
        "--numCols", "500",
        "--rank", "10",
        "--symmetric", "true",
        "--workingDir", workingDir.toString()
    };
    new DistributedLanczosSolver().new DistributedLanczosSolverJob().run(args);

    Path rawEigenvectors = new Path(output, DistributedLanczosSolver.RAW_EIGENVECTORS);
    Matrix eigenVectors = new DenseMatrix(10, corpus.numCols());
    Configuration conf = new Configuration();

    int i = 0;
    for (VectorWritable value : new SequenceFileValueIterable<VectorWritable>(rawEigenvectors, conf)) {
      Vector v = value.get();
      eigenVectors.assignRow(i, v);
      i++;
    }
    assertEquals("number of eigenvectors", 10, i);
  }

  @Test
  public void testDistributedLanczosSolverEVJCLI() throws Exception {
    Path testData = getTestTempDirPath("testdata");
    DistributedRowMatrix corpus = new TestDistributedRowMatrix()
        .randomDistributedMatrix(500, 450, 500, 10, 10.0, true, testData.toString());
    corpus.setConf(new Configuration());
    Path output = getTestTempDirPath("output");
    Path tmp = getTestTempDirPath("tmp");
    String[] args = {
        "-i", new Path(testData, "distMatrix").toString(),
        "-o", output.toString(),
        "--tempDir", tmp.toString(),
        "--numRows", "500",
        "--numCols", "500",
        "--rank", "10",
        "--symmetric", "true",
        "--cleansvd", "true"
    };
    new DistributedLanczosSolver().new DistributedLanczosSolverJob().run(args);
  
    Path cleanEigenvectors = new Path(output, EigenVerificationJob.CLEAN_EIGENVECTORS);
    Matrix eigenVectors = new DenseMatrix(10, corpus.numCols());
    Configuration conf = new Configuration();
    List<Double> eigenvalues = new ArrayList<Double>();

    int i = 0;
    for (VectorWritable value : new SequenceFileValueIterable<VectorWritable>(cleanEigenvectors, conf)) {
      NamedVector v = (NamedVector) value.get();
      eigenVectors.assignRow(i, v);
      log.info(v.getName());
      eigenvalues.add(EigenVector.parseMetaData(v.getName())[1]);
      i++;
    }
    assertEquals("number of clean eigenvectors", 4, i);

    output = getTestTempDirPath("output2");
    tmp = getTestTempDirPath("tmp2");
    args = new String[] {
        "-i", new Path(testData, "distMatrix").toString(),
        "-o", output.toString(),
        "--tempDir", tmp.toString(),
        "--numRows", "500",
        "--numCols", "500",
        "--rank", "20",
        "--symmetric", "true",
        "--cleansvd", "true"
    };
    new DistributedLanczosSolver().new DistributedLanczosSolverJob().run(args);
    cleanEigenvectors = new Path(output, EigenVerificationJob.CLEAN_EIGENVECTORS);
    Matrix eigenVectors2 = new DenseMatrix(20, corpus.numCols());
    conf = new Configuration();
    List<Double> newEigenValues = new ArrayList<Double>();
    i=0;
    for (VectorWritable value : new SequenceFileValueIterable<VectorWritable>(cleanEigenvectors, conf)) {
      NamedVector v = (NamedVector) value.get();
      log.info(v.getName());
      eigenVectors2.assignRow(i, v);
      newEigenValues.add(EigenVector.parseMetaData(v.getName())[1]);
      i++;
    }
    List<Integer> oldEigensFound = new ArrayList<Integer>();
    for(int row = 0; row < 4; row++) {
      Vector oldEigen = eigenVectors.getRow(row);
      for(int newRow = 0; newRow < 20; newRow++) {
        Vector newEigen = eigenVectors2.getRow(newRow);
        if(newEigen != null) {
          if(oldEigen.minus(newEigen).norm(2) < 0.1) {
            oldEigensFound.add(row);
          }
        }
      }
    }
    assertEquals("the number of new eigenvectors", 10, i);

    List<Double> oldEigenValuesNotFound = new ArrayList<Double>();
    for(double d : eigenvalues) {
      boolean found = false;
      for(double newD : newEigenValues) {
        if(Math.abs(d - newD) < 1e-4) {
          found = true;
        }
      }
      if(!found) {
        oldEigenValuesNotFound.add(d);
      }
    }
    assertEquals("number of old eigenvalues not found: "
                 + Arrays.toString(oldEigenValuesNotFound.toArray(new Double[0])),
                0, oldEigenValuesNotFound.size());
    assertEquals("did not find all old eigenvectors", 4, oldEigensFound.size());
  }

}
