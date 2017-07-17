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
package org.apache.spark.ml.h2o.algos

import java.io._

import hex.{FrameSplitter, Model}
import org.apache.hadoop.fs.Path
import org.apache.spark.annotation.{DeveloperApi, Since}
import org.apache.spark.h2o._
import org.apache.spark.ml.h2o.algos.params.H2OAlgoParams
import org.apache.spark.ml.param._
import org.apache.spark.ml.util._
import org.apache.spark.ml.{Estimator, Model => SparkModel}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Dataset, SQLContext}
import water.{DKV, Key}
import water.fvec.{Frame, H2OFrame}
import water.support.H2OFrameSupport

import scala.reflect.ClassTag

/**
  * Base class for H2O algorithm wrapper as a Spark transformer.
  */
abstract class H2OAlgorithm[P <: Model.Parameters : ClassTag, M <: SparkModel[M] : ClassTag]
(parameters: Option[P])
(implicit hc: H2OContext, sqlContext: SQLContext)
  extends Estimator[M] with MLWritable with H2OAlgoParams[P] {

  type SELF

  if (parameters.isDefined) {
    setParams(parameters.get)
  }

  override def fit(dataset: Dataset[_]): M = {
    import org.apache.spark.sql.functions.col

    // if this is left empty select all
    if (getFeaturesCols.isEmpty) {
      setFeaturesCols(dataset.columns)
    }

    val cols = getFeaturesCols.map(col) ++ Array(col(getPredictionsCol))
    val input = hc.asH2OFrame(dataset.select(cols: _*).toDF())

    // check if we need to do any splitting

    if ($(ratio) < 1.0) {
      // need to do splitting
      val keys = split(input, hc)
      getParams._train = keys(0)
      if (keys.length > 1) {
        getParams._valid = keys(1)
      }
    } else {
      getParams._train = input._key
    }

    val trainFrame = H2OFrameSupport.allStringVecToCategorical(getParams._train.get())
    water.DKV.put(trainFrame)

    // Train
    val model: M = trainModel(getParams)
    model.set("featuresCols", $(featuresCols))
    model.set("predictionCol", $(featuresCols))
    // pass some parameters set on algo to model
    model
  }

  def trainModel(params: P): M

  @DeveloperApi
  override def transformSchema(schema: StructType): StructType = schema

  override def copy(extra: ParamMap): this.type = defaultCopy(extra)

  @Since("1.6.0")
  override def write: MLWriter = new H2OAlgorithmWriter(this)

  private def split(fr: H2OFrame, hc: H2OContext): Array[Key[Frame]] = {
    val trainKey = Key.make[Frame]("train")
    val validKey = Key.make[Frame]("valid")
    val keys = Array(trainKey, validKey)
    val ratios = Array[Double]($(ratio))

    val splitter = new FrameSplitter(fr, ratios, keys, null)
    water.H2O.submitTask(splitter)
    // return results
    splitter.getResult

    keys
  }


  /**
    * By default it is set to 1.0 which use whole frame for training
    */
  final val ratio = new DoubleParam(this, "ratio", "Determines in which ratios split the dataset")
  final val predictionCol: Param[String] = new Param[String](this, "predictionCol", "Prediction column name")
  final val featuresCols: StringArrayParam = new StringArrayParam(this, "featuresCols", "Name of feature columns")
  setDefault(ratio -> 1.0)
  setDefault(predictionCol -> "prediction")
  setDefault(featuresCols -> Array.empty[String])

  /** @group getParam */
  def getTrainRatio: Double = $(ratio)

  /** @group setParam */
  def setTrainRatio(value: Double) = set(ratio, value) {}

  /** @group getParam */
  def getPredictionsCol: String = $(predictionCol)

  /** @group setParam */
  def setPredictionsCol(value: String) = set(predictionCol, value) {
    getParams._response_column = value
  }

  /** @group getParam */
  final def getFeaturesCols: Array[String] = $(featuresCols)

  /** @group setParam */
  def setFeaturesCols(first: String, others: String*) = set(featuresCols, Array(first) ++ others) {}

  /** @group setParam */
  def setFeaturesCols(cols: Array[String]) = {
    if (cols.length == 0) {
      throw new IllegalArgumentException("Array with feature columns must contain at least one column")
    }
    set(featuresCols, cols) {}
  }

  def setFeaturesCol(first: String) = setFeaturesCols(first)

  /**
    * Set the param and execute custom piece of code
    */
  protected final def set[T](param: Param[T], value: T)(f: => Unit): SELF = {
    f
    super.set(param, value).asInstanceOf[SELF]
  }

  def defaultFileName: String
}

// FIXME: H2O Params are iced objects!
private[algos] class H2OAlgorithmWriter[T <: H2OAlgorithm[_, _]](instance: T) extends MLWriter {

  @Since("1.6.0") override protected def saveImpl(path: String): Unit = {
    val hadoopConf = sc.hadoopConfiguration
    DefaultParamsWriter.saveMetadata(instance, path, sc)
    val outputPath = if (path.startsWith("file://")) {
      new Path(path, instance.defaultFileName)
    } else {
      new Path("file://" + path, instance.defaultFileName)
    }
    val fs = outputPath.getFileSystem(hadoopConf)
    val qualifiedOutputPath = outputPath.makeQualified(fs.getUri, fs.getWorkingDirectory)
    fs.create(qualifiedOutputPath)
    val oos = new ObjectOutputStream(new FileOutputStream(new File(qualifiedOutputPath.toUri), false))
    oos.writeObject(instance.getParams)
  }
}

private[algos] class H2OAlgorithmReader[A <: H2OAlgorithm[P, _] : ClassTag, P <: Model.Parameters : ClassTag]
(val defaultFileName: String) extends MLReader[A] {

  private val className = implicitly[ClassTag[A]].runtimeClass.getName

  override def load(path: String): A = {
    val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
    val file = new File(path, defaultFileName)
    val ois = new ObjectInputStream(new FileInputStream(file))
    val parameters = ois.readObject().asInstanceOf[P]
    implicit val h2oContext = H2OContext.ensure("H2OContext has to be started in order to use H2O pipelines elements.")
    val h2oAlgo = make[A, P](parameters, metadata.uid, h2oContext, sqlContext)
    DefaultParamsReader.getAndSetParams(h2oAlgo, metadata)
    h2oAlgo
  }

  private def make[CT: ClassTag, X <: Object : ClassTag]
  (p: X, uid: String, h2oContext: H2OContext, sqlContext: SQLContext): CT = {
    val pClass = implicitly[ClassTag[X]].runtimeClass
    val aClass = implicitly[ClassTag[CT]].runtimeClass
    val ctor = aClass.getConstructor(pClass, classOf[String], classOf[H2OContext], classOf[SQLContext])
    ctor.newInstance(p, uid, h2oContext, sqlContext).asInstanceOf[CT]
  }
}

