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

package org.apache.spark.ml.h2o.models

import java.io._

import hex.ModelCategory
import hex.genmodel.easy.{EasyPredictModelWrapper, RowData}
import hex.genmodel.{ModelMojoReader, MojoModel, MojoReaderBackendFactory}
import org.apache.spark._
import org.apache.spark.annotation.{DeveloperApi, Since}
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util._
import org.apache.spark.ml.{Model => SparkModel}
import org.apache.spark.sql._
import org.apache.spark.sql.types._

import scala.reflect.ClassTag

class H2OMOJOModel(val model: MojoModel, val mojoData: Array[Byte], override val uid: String)(sqlContext: SQLContext)
  extends SparkModel[H2OMOJOModel] with H2OModelParams with MLWritable {

  def this(model: MojoModel, mojoData: Array[Byte])(sqlContext: SQLContext) = this(model, mojoData, Identifiable.randomUID("mojoModel"))(sqlContext)

  override def copy(extra: ParamMap): H2OMOJOModel = defaultCopy(extra)

  private def flattenSchemaToCol(schema: StructType, prefix: String = null): Array[Column] = {
    import org.apache.spark.sql.functions.col

    schema.fields.flatMap { f =>
      val colName = if (prefix == null) f.name else prefix + "." + f.name

      f.dataType match {
        case st: StructType => flattenSchemaToCol(st, colName)
        case _ => Array[Column](col(colName))
      }
    }
  }

  def defaultFileName: String = H2OMOJOModel.defaultFileName
  private def flattenDataFrame(df: DataFrame): DataFrame = {
    import org.apache.spark.sql.functions.col
    val flattenSchema = flattenSchemaToCol(df.schema)
    // this is needed so the flattened data frame has hiearchical names
    val renamedCols = flattenSchema.map(name => col(name.toString()).as(name.toString()))
    df.select(renamedCols: _*)
  }


  override def transform(dataset: Dataset[_]): DataFrame = {


    val spark = SparkSession.builder().getOrCreate()
    val df = flattenDataFrame(dataset.toDF())

    val predictedRows = df.rdd.map { row =>
      // create RowData on which we do the predictions
      val dt = new RowData
      row.schema.fields.zipWithIndex.foreach { case (entry, idxRow) =>
        if (!$(featuresCols).contains(entry.name)) {
          // ignore this column
        } else {
          if (row.isNullAt(idxRow)) {
            dt.put(entry.name, 0.toString) // 0 as NA
          } else {
            entry.dataType match {
              case BooleanType => if (row.getBoolean(idxRow)) dt.put(entry.name, 1.toString) else dt.put(entry.name, 0.toString)
              case BinaryType =>
              case ByteType => dt.put(entry.name, row.getByte(idxRow).toString)
              case ShortType => dt.put(entry.name, row.getShort(idxRow).toString)
              case IntegerType => dt.put(entry.name, row.getInt(idxRow).toString)
              case LongType => dt.put(entry.name, row.getLong(idxRow).toString)
              case FloatType => dt.put(entry.name, row.getFloat(idxRow).toString)
              case _: DecimalType => dt.put(entry.name, row.getDecimal(idxRow).doubleValue().toString)
              case DoubleType => dt.put(entry.name, row.getDouble(idxRow).toString)
              case StringType => dt.put(entry.name, row.getString(idxRow))
              case TimestampType => dt.put(entry.name, row.getAs[java.sql.Timestamp](idxRow).getTime.toString)
              case DateType => dt.put(entry.name, row.getAs[java.sql.Date](idxRow).getTime.toString)
              case ArrayType(_, _) => // for now assume that all arrays and vecs have the same size - we can store max size as part of the model
                row.getAs[Seq[_]](idxRow).zipWithIndex.foreach { case (v, idx) =>
                  dt.put(entry.name + idx, v.toString)
                }
              case _: UserDefinedType[_ /*mllib.linalg.Vector*/ ] =>
                val value = row.get(idxRow)
                value match {
                  case vector: mllib.linalg.Vector =>
                    (0 until vector.size).foreach { idx =>
                      dt.put(entry.name + idx, vector(idx).toString)
                    }
                  case vector: ml.linalg.Vector =>
                    (0 until vector.size).foreach { idx =>
                      dt.put(entry.name + idx, vector(idx).toString)
                    }
                }
              case _ => dt.put(entry.name, 0.toString)
            }
          }
        }
      }
      //import scala.collection.JavaConversions._
      //Row.merge(Row.fromSeq(dt.values().toSeq), predict(dt))
      predict(dt)
    }

    spark.createDataFrame(predictedRows, getPredictionFrameSchema())
  }

  def getPredictionFrameSchema(): StructType = {
    val wr = new EasyPredictModelWrapper(model)
    model.getModelCategory match {
      case ModelCategory.Binomial => StructType(wr.getResponseDomainValues.map(StructField(_, DoubleType)))
      case ModelCategory.Multinomial => StructType(wr.getResponseDomainValues.map(StructField(_, DoubleType)))
      case ModelCategory.Regression => StructType(Seq(StructField("value", DoubleType)))
      case ModelCategory.Clustering => StructType(Seq(StructField("cluster", DoubleType)))
      case ModelCategory.AutoEncoder => throw new RuntimeException("UnImplemented")
      case ModelCategory.DimReduction => throw new RuntimeException("UnImplemented") //Row(wr.predictDimReduction(data).dimensions)
      case ModelCategory.WordEmbedding => throw new RuntimeException("UnImplemented") //Row(wr.predictWord2Vec(data).wordEmbeddings)
      case ModelCategory.Unknown => throw new RuntimeException("Unknown")
    }
  }

  def predict(data: RowData): Row = {
    val wr = new EasyPredictModelWrapper(model)
    model.getModelCategory match {
      case ModelCategory.Binomial => Row(wr.predictBinomial(data).classProbabilities: _*)
      case ModelCategory.Multinomial => Row(wr.predictMultinomial(data).classProbabilities: _*)
      case ModelCategory.Regression => Row(wr.predictRegression(data).value)
      case ModelCategory.Clustering => Row(wr.predictClustering(data).cluster)
      case ModelCategory.AutoEncoder => throw new RuntimeException("UnImplemented")
      case ModelCategory.DimReduction => throw new RuntimeException("UnImplemented") //Row(wr.predictDimReduction(data).dimensions)
      case ModelCategory.WordEmbedding => throw new RuntimeException("UnImplemented") //Row(wr.predictWord2Vec(data).wordEmbeddings)
      case ModelCategory.Unknown => throw new RuntimeException("Unknown")
    }
  }

  @DeveloperApi
  override def transformSchema(schema: StructType): StructType = {
    val ncols: Int = if (model.nclasses() == 1) 1 else model.nclasses() + 1
    StructType(model.getNames.map {
      name => StructField(name, DoubleType, nullable = true, metadata = null)
    })
  }

  @Since("1.6.0")
  override def write: MLWriter = new H2OMOJOModelWriter(this)

}

private[models] class H2OMOJOModelWriter(instance: H2OMOJOModel) extends MLWriter {

  @org.apache.spark.annotation.Since("1.6.0")
  override protected def saveImpl(path: String): Unit = {
    DefaultParamsWriter.saveMetadata(instance, path, sc)
    val file = new java.io.File(path, instance.defaultFileName)
    val fos = new FileOutputStream(file)
    fos.write(instance.mojoData)
  }
}

private[models] class H2OMOJOModelReader
(val defaultFileName: String) extends MLReader[H2OMOJOModel] {

  private val className = implicitly[ClassTag[H2OMOJOModel]].runtimeClass.getName

  @org.apache.spark.annotation.Since("1.6.0")
  override def load(path: String): H2OMOJOModel = {
    val metadata = DefaultParamsReader.loadMetadata(path, sc, className)
    val file = new File(path, defaultFileName)
    val is = new FileInputStream(file)
    val mojoData = Stream.continually(is.read).takeWhile(_ != -1).map(_.toByte).toArray
    val reader = MojoReaderBackendFactory.createReaderBackend(is, MojoReaderBackendFactory.CachingStrategy.MEMORY)
    val model = ModelMojoReader.readFrom(reader)
    val h2oModel = make(model, mojoData, metadata.uid)(sqlContext)
    DefaultParamsReader.getAndSetParams(h2oModel, metadata)
    h2oModel
  }

  def make(model: MojoModel, mojoData: Array[Byte], uid: String)(sqLContext: SQLContext): H2OMOJOModel = {
    new H2OMOJOModel(model, mojoData, uid)(sqlContext)
  }
}

object H2OMOJOModel extends MLReadable[H2OMOJOModel]{
  val defaultFileName = "mojo_model"

    @Since("1.6.0")
    override def read: MLReader[H2OMOJOModel] = new H2OMOJOModelReader(defaultFileName)
    @Since("1.6.0")
    override def load(path: String): H2OMOJOModel = super.load(path)
}