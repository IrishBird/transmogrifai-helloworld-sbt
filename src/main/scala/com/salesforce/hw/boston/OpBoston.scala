/*
 * Copyright (c) 2018, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE.txt file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.hw.boston

import com.salesforce.op._
import com.salesforce.op.evaluators.Evaluators
import com.salesforce.op.readers.CustomReader
import com.salesforce.op.stages.impl.regression.RegressionModelSelector
import com.salesforce.op.stages.impl.regression.RegressionModelsToTry._
import com.salesforce.op.stages.impl.tuning.DataSplitter
import com.salesforce.op.utils.kryo.OpKryoRegistrator
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Dataset, SparkSession}

/**
 * TransmogrifAI Regression example on the Boston Dataset
 */
object OpBoston extends OpAppWithRunner with BostonFeatures {

  override def kryoRegistrator: Class[_ <: OpKryoRegistrator] = classOf[BostonKryoRegistrator]

  ////////////////////////////////////////////////////////////////////////////////
  // READERS DEFINITION
  /////////////////////////////////////////////////////////////////////////////////

  val randomSeed = 112233L

  def customRead(path: Option[String], spark: SparkSession): RDD[BostonHouse] = {
    require(path.isDefined, "The path is not set")
    val myFile = spark.sparkContext.textFile(path.get)

    myFile.filter(_.nonEmpty).zipWithIndex.map { case (x, number) =>
      val words = x.replaceAll("\\s+", " ").replaceAll(s"^\\s+(?m)", "").replaceAll(s"(?m)\\s+$$", "").split(" ")
      BostonHouse(number.toInt, words(0).toDouble, words(1).toDouble, words(2).toDouble, words(3), words(4).toDouble,
        words(5).toDouble, words(6).toDouble, words(7).toDouble, words(8).toInt, words(9).toDouble,
        words(10).toDouble, words(11).toDouble, words(12).toDouble, words(13).toDouble)
    }
  }

  val trainingReader = new CustomReader[BostonHouse](key = _.rowId.toString) {
    def readFn(params: OpParams)(implicit spark: SparkSession): Either[RDD[BostonHouse], Dataset[BostonHouse]] = {
      val Array(train, _) = customRead(Some(getFinalReadPath(params)), spark).randomSplit(weights = Array(0.9, 0.1),
        seed = randomSeed)
      Left(train)
    }
  }

  val scoringReader = new CustomReader[BostonHouse](key = _.rowId.toString) {
    def readFn(params: OpParams)(implicit spark: SparkSession): Either[RDD[BostonHouse], Dataset[BostonHouse]] = {
      val Array(_, test) = customRead(Some(getFinalReadPath(params)), spark).randomSplit(weights = Array(0.9, 0.1),
        seed = randomSeed)
      Left(test)
    }
  }


  ////////////////////////////////////////////////////////////////////////////////
  // WORKFLOW DEFINITION
  /////////////////////////////////////////////////////////////////////////////////

  val houseFeatures = Seq(crim, zn, indus, chas, nox, rm, age, dis, rad, tax, ptratio, b, lstat).transmogrify()

  val prediction = RegressionModelSelector
    .withCrossValidation(
      dataSplitter = Some(DataSplitter(seed = randomSeed)), seed = randomSeed,
      modelTypesToUse = Seq(OpGBTRegressor, OpRandomForestRegressor)
    ).setInput(medv, houseFeatures).getOutput()

  val workflow = new OpWorkflow().setResultFeatures(prediction)

  val evaluator = Evaluators.Regression().setLabelCol(medv).setPredictionCol(prediction)

  def runner(opParams: OpParams): OpWorkflowRunner =
    new OpWorkflowRunner(
      workflow = workflow,
      trainingReader = trainingReader,
      scoringReader = scoringReader,
      evaluationReader = Option(trainingReader),
      evaluator = Option(evaluator),
      scoringEvaluator = None,
      featureToComputeUpTo = Option(houseFeatures)
    )

}
