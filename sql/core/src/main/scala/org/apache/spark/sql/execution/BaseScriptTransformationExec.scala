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

package org.apache.spark.sql.execution

import java.io.{BufferedReader, InputStream, OutputStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.hadoop.conf.Configuration

import org.apache.spark.{SparkException, TaskContext}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeSet, Expression, GenericInternalRow, UnsafeProjection}
import org.apache.spark.sql.catalyst.plans.logical.ScriptInputOutputSchema
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.catalyst.util.{DateTimeUtils, IntervalUtils}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.{CircularBuffer, RedirectThread, SerializableConfiguration, Utils}

trait BaseScriptTransformationExec extends UnaryExecNode {
  def input: Seq[Expression]
  def script: String
  def output: Seq[Attribute]
  def child: SparkPlan
  def ioschema: ScriptTransformationIOSchema

  override def producedAttributes: AttributeSet = outputSet -- inputSet

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def doExecute(): RDD[InternalRow] = {
    val broadcastedHadoopConf =
      new SerializableConfiguration(sqlContext.sessionState.newHadoopConf())

    child.execute().mapPartitions { iter =>
      if (iter.hasNext) {
        val proj = UnsafeProjection.create(schema)
        processIterator(iter, broadcastedHadoopConf.value).map(proj)
      } else {
        // If the input iterator has no rows then do not launch the external script.
        Iterator.empty
      }
    }
  }

  protected def initProc: (OutputStream, Process, InputStream, CircularBuffer) = {
    val cmd = List("/bin/bash", "-c", script)
    val builder = new ProcessBuilder(cmd.asJava)

    val proc = builder.start()
    val inputStream = proc.getInputStream
    val outputStream = proc.getOutputStream
    val errorStream = proc.getErrorStream

    // In order to avoid deadlocks, we need to consume the error output of the child process.
    // To avoid issues caused by large error output, we use a circular buffer to limit the amount
    // of error output that we retain. See SPARK-7862 for more discussion of the deadlock / hang
    // that motivates this.
    val stderrBuffer = new CircularBuffer(2048)
    new RedirectThread(
      errorStream,
      stderrBuffer,
      s"Thread-${this.getClass.getSimpleName}-STDERR-Consumer").start()
    (outputStream, proc, inputStream, stderrBuffer)
  }

  protected def processIterator(
      inputIterator: Iterator[InternalRow],
      hadoopConf: Configuration): Iterator[InternalRow]

  protected def processOutputWithoutSerde(prevLine: String, reader: BufferedReader): InternalRow = {
    if (!ioschema.schemaLess) {
      new GenericInternalRow(
        prevLine.split(ioschema.outputRowFormatMap("TOK_TABLEROWFORMATFIELD"))
          .zip(fieldWriters)
          .map { case (data, writer) => writer(data) })
    } else {
      new GenericInternalRow(
        prevLine.split(ioschema.outputRowFormatMap("TOK_TABLEROWFORMATFIELD"), 2)
          .zip(fieldWriters)
          .map { case (data, writer) => writer(data) })
    }
  }

  protected def checkFailureAndPropagate(
      writerThread: BaseScriptTransformationWriterThread,
      cause: Throwable = null,
      proc: Process,
      stderrBuffer: CircularBuffer): Unit = {
    if (writerThread.exception.isDefined) {
      throw writerThread.exception.get
    }

    // There can be a lag between reader read EOF and the process termination.
    // If the script fails to startup, this kind of error may be missed.
    // So explicitly waiting for the process termination.
    val timeout = conf.getConf(SQLConf.SCRIPT_TRANSFORMATION_EXIT_TIMEOUT)
    val exitRes = proc.waitFor(timeout, TimeUnit.SECONDS)
    if (!exitRes) {
      log.warn(s"Transformation script process exits timeout in $timeout seconds")
    }

    if (!proc.isAlive) {
      val exitCode = proc.exitValue()
      if (exitCode != 0) {
        logError(stderrBuffer.toString) // log the stderr circular buffer
        throw new SparkException(s"Subprocess exited with status $exitCode. " +
          s"Error: ${stderrBuffer.toString}", cause)
      }
    }
  }

  private lazy val fieldWriters: Seq[String => Any] = output.map { attr =>
    val converter = CatalystTypeConverters.createToCatalystConverter(attr.dataType)
    attr.dataType match {
      case StringType => (data: String) => converter(data)
      case ByteType => (data: String) => converter(data.toByte)
      case IntegerType => (data: String) => converter(data.toInt)
      case ShortType => (data: String) => converter(data.toShort)
      case LongType => (data: String) => converter(data.toLong)
      case FloatType => (data: String) => converter(data.toFloat)
      case DoubleType => (data: String) => converter(data.toDouble)
      case dt: DecimalType => (data: String) => converter(BigDecimal(data))
      case DateType if conf.datetimeJava8ApiEnabled => (data: String) =>
        converter(DateTimeUtils.stringToDate(
          UTF8String.fromString(data),
          DateTimeUtils.getZoneId(conf.sessionLocalTimeZone))
          .map(DateTimeUtils.daysToLocalDate).orNull)
      case DateType => (data: String) =>
        converter(DateTimeUtils.stringToDate(
          UTF8String.fromString(data),
          DateTimeUtils.getZoneId(conf.sessionLocalTimeZone))
          .map(DateTimeUtils.toJavaDate).orNull)
      case TimestampType if conf.datetimeJava8ApiEnabled => (data: String) =>
        converter(DateTimeUtils.stringToTimestamp(
          UTF8String.fromString(data),
          DateTimeUtils.getZoneId(conf.sessionLocalTimeZone))
          .map(DateTimeUtils.microsToInstant).orNull)
      case TimestampType => (data: String) =>
        converter(DateTimeUtils.stringToTimestamp(
          UTF8String.fromString(data),
          DateTimeUtils.getZoneId(conf.sessionLocalTimeZone))
          .map(DateTimeUtils.toJavaTimestamp).orNull)
      case CalendarIntervalType => (data: String) =>
        converter(IntervalUtils.stringToInterval(UTF8String.fromString(data)))
      case dataType: DataType => (data: String) => converter(data)
    }
  }
}

abstract class BaseScriptTransformationWriterThread extends Thread with Logging {

  def iter: Iterator[InternalRow]
  def inputSchema: Seq[DataType]
  def ioSchema: ScriptTransformationIOSchema
  def outputStream: OutputStream
  def proc: Process
  def stderrBuffer: CircularBuffer
  def taskContext: TaskContext
  def conf: Configuration

  setDaemon(true)

  @volatile protected var _exception: Throwable = null

  /** Contains the exception thrown while writing the parent iterator to the external process. */
  def exception: Option[Throwable] = Option(_exception)

  protected def processRows(): Unit

  protected def processRowsWithoutSerde(): Unit = {
    val len = inputSchema.length
    iter.foreach { row =>
      val data = if (len == 0) {
        ioSchema.inputRowFormatMap("TOK_TABLEROWFORMATLINES")
      } else {
        val sb = new StringBuilder
        sb.append(row.get(0, inputSchema(0)))
        var i = 1
        while (i < len) {
          sb.append(ioSchema.inputRowFormatMap("TOK_TABLEROWFORMATFIELD"))
          sb.append(row.get(i, inputSchema(i)))
          i += 1
        }
        sb.append(ioSchema.inputRowFormatMap("TOK_TABLEROWFORMATLINES"))
        sb.toString()
      }
      outputStream.write(data.getBytes(StandardCharsets.UTF_8))
    }
  }

  override def run(): Unit = Utils.logUncaughtExceptions {
    TaskContext.setTaskContext(taskContext)

    // We can't use Utils.tryWithSafeFinally here because we also need a `catch` block, so
    // let's use a variable to record whether the `finally` block was hit due to an exception
    var threwException: Boolean = true
    try {
      processRows()
      threwException = false
    } catch {
      // SPARK-25158 Exception should not be thrown again, otherwise it will be captured by
      // SparkUncaughtExceptionHandler, then Executor will exit because of this Uncaught Exception,
      // so pass the exception to `ScriptTransformationExec` is enough.
      case t: Throwable =>
        // An error occurred while writing input, so kill the child process. According to the
        // Javadoc this call will not throw an exception:
        _exception = t
        proc.destroy()
        logError("Thread-ScriptTransformation-Feed exit cause by: ", t)
    } finally {
      try {
        Utils.tryLogNonFatalError(outputStream.close())
        if (proc.waitFor() != 0) {
          logError(stderrBuffer.toString) // log the stderr circular buffer
        }
      } catch {
        case NonFatal(exceptionFromFinallyBlock) =>
          if (!threwException) {
            throw exceptionFromFinallyBlock
          } else {
            log.error("Exception in finally block", exceptionFromFinallyBlock)
          }
      }
    }
  }
}

/**
 * The wrapper class of input and output schema properties
 */
case class ScriptTransformationIOSchema(
    inputRowFormat: Seq[(String, String)],
    outputRowFormat: Seq[(String, String)],
    inputSerdeClass: Option[String],
    outputSerdeClass: Option[String],
    inputSerdeProps: Seq[(String, String)],
    outputSerdeProps: Seq[(String, String)],
    recordReaderClass: Option[String],
    recordWriterClass: Option[String],
    schemaLess: Boolean) extends Serializable {
  import ScriptTransformationIOSchema._

  val inputRowFormatMap = inputRowFormat.toMap.withDefault((k) => defaultFormat(k))
  val outputRowFormatMap = outputRowFormat.toMap.withDefault((k) => defaultFormat(k))
}

object ScriptTransformationIOSchema {
  val defaultFormat = Map(
    ("TOK_TABLEROWFORMATFIELD", "\t"),
    ("TOK_TABLEROWFORMATLINES", "\n")
  )

  def apply(input: ScriptInputOutputSchema): ScriptTransformationIOSchema = {
    ScriptTransformationIOSchema(
      input.inputRowFormat,
      input.outputRowFormat,
      input.inputSerdeClass,
      input.outputSerdeClass,
      input.inputSerdeProps,
      input.outputSerdeProps,
      input.recordReaderClass,
      input.recordWriterClass,
      input.schemaLess)
  }
}
