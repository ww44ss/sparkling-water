/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.ml.spark.models.svm

import hex.{ModelBuilder, ModelCategory, ModelMetricsBinomial, ModelMetricsRegression}
import org.apache.spark.h2o.H2OContext
import org.apache.spark.ml.spark.models.svm.SVMModel.{SVMOutput, SVMParameters}
import org.apache.spark.mllib.classification.SVMWithSGD
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SQLContext
import water.Scope
import water.app.ModelMetricsSupport
import water.fvec.{Frame, H2OFrame, Vec}
import water.util.Log

class SVM(val startup_once: Boolean) extends
  ModelBuilder[SVMModel, SVMParameters, SVMOutput](new SVMModel.SVMParameters(), startup_once) {

  @transient private val sc = H2OContext.getSparkContext()
  @transient private val h2oContext = H2OContext.getOrCreate(sc)
  @transient private implicit val sqlContext = SQLContext.getOrCreate(sc)

  override protected def trainModelImpl(): Driver = new SVMDriver()

  override def can_build(): Array[ModelCategory] =
    Array(
      ModelCategory.Binomial,
      ModelCategory.Regression
    )

  override def isSupervised: Boolean = true

  override def init(expensive: Boolean): Unit = {
    super.init(expensive)
    if (_parms._max_iterations < 0 || _parms._max_iterations > 1e6) {
      error("_max_iterations", " max_iterations must be between 0 and 1e6")
    }
    if (_train == null) return
    if (null != _parms._initial_weights) {
      val user_points = _parms._initial_weights.get()
      // -1 because of response column
      if (user_points.numCols() != _train.numCols() - numSpecialCols()) {
        error("_user_y",
          s"The user-specified points must have the same number of columns " +
            s"(${_train.numCols() - numSpecialCols()}) as the training observations")
      }
    }

    if (
      (null == _parms.train().domains()(_parms.train().find(_parms._response_column))) &&
        !_parms._threshold.isNaN) {
      error("_threshold", "Threshold cannot be set for regression SVM.")
    } else if (
      (null != _parms.train().domains()(_parms.train().find(_parms._response_column))) &&
        _parms._threshold.isNaN) {
      error("_threshold", "Threshold has to be set for binomial SVM.")
    }
  }

  override def numSpecialCols(): Int =
    (if (hasOffsetCol) 1 else 0) +
      (if (hasWeightCol) 1 else 0) +
      (if (hasFoldCol) 1 else 0) + 1

  private class SVMDriver extends Driver with ModelMetricsSupport {

    override def compute2(): Unit = {
      var model: SVMModel = null
      try {
        Scope.enter()
        _parms.read_lock_frames(_job)
        init(true)

        // The model to be built
        model = new SVMModel(dest(), _parms, new SVMModel.SVMOutput(SVM.this))
        model.delete_and_lock(_job)

        val training: RDD[LabeledPoint] = getTrainingData(
          _train,
          _parms._response_column,
          model._output.nfeatures()
        )
        training.cache()

        val svm: SVMWithSGD = new SVMWithSGD()
        svm.setIntercept(_parms._add_intercept)

        svm.optimizer.setNumIterations(_parms._max_iterations)

        svm.optimizer.setStepSize(_parms._step_size)
        svm.optimizer.setRegParam(_parms._reg_param)
        svm.optimizer.setMiniBatchFraction(_parms._mini_batch_fraction)
        svm.optimizer.setConvergenceTol(_parms._convergence_tol)
        svm.optimizer.setGradient(_parms._gradient.get())
        svm.optimizer.setUpdater(_parms._updater.get())

        /**
          * TODO should we try and implement job cancellation?
          * One idea would be to run the below code in a different thread
          * get the spark JOB and try to cancel it when the user presses cancel.
          * The problem is we won't get any model then, we cannot take intermediate
          * results like in our own impls.
         */
        val trainedModel: org.apache.spark.mllib.classification.SVMModel =
          if (null == _parms._initial_weights) {
            svm.run(training)
          } else {
            svm.run(training, vec2vec(_parms.initialWeights().vecs()))
          }
        training.unpersist(false)

        model._output.weights = trainedModel.weights.toArray
        model._output.interceptor = trainedModel.intercept
        model.update(_job)
        // TODO how to update from Spark hmmm?
        _job.update(model._parms._max_iterations)

        if (_valid != null) {
          model.score(_parms.valid()).delete()
          model._output._validation_metrics =
            if (nclasses() == 1){
              modelMetrics[ModelMetricsBinomial](model, _train)
            }
            else {
              modelMetrics[ModelMetricsRegression](model, _train)
            }
          model.update(_job)
        }

        Log.info(model._output._model_summary)
      } finally {
        if (model != null) model.unlock(_job)
        _parms.read_unlock_frames(_job)
        Scope.exit()
      }
      tryComplete()
    }

    private def vec2vec(vals: Array[Vec]): Vector = Vectors.dense(vals.map(_.at(0)))

    private def getTrainingData(@transient parms: Frame, _response_column: String, nfeatures: Int): RDD[LabeledPoint] = {
      val domains = parms.domains()(parms.find(_response_column))
      h2oContext.createH2OSchemaRDD(new H2OFrame(parms)).rdd.map { row =>
        def toDoubleLabel(label: Any): Double = label match {
          case stringLabel: String => domains.indexOf(stringLabel).toDouble
          case n: Byte => n.toDouble
          case n: Int => n.toDouble
          case n: Double => n.toDouble
          case _ => throw new IllegalArgumentException("Target column has to be an enum or a number.")
        }

        new LabeledPoint(
          toDoubleLabel(row.getAs(_response_column)),
          Vectors.dense((0 until nfeatures).map(row.getDouble).toArray))
      }
    }

  }

}
