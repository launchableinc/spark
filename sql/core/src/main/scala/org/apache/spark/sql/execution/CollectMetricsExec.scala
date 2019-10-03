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

import scala.collection.mutable

import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.{CatalystTypeConverters, InternalRow}
import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression, SortOrder}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.types.StructType

/**
 * Collect arbitrary (named) metrics from a [[SparkPlan]].
 */
case class CollectMetricsExec(
    name: String,
    metricExpressions: Seq[NamedExpression],
    child: SparkPlan)
  extends UnaryExecNode {

  private lazy val accumulator: AggregatingAccumulator = {
    val acc = AggregatingAccumulator(metricExpressions, child.output)
    acc.register(sparkContext, Option("Collected metrics"))
    acc
  }

  val metricsSchema: StructType = {
    StructType.fromAttributes(metricExpressions.map(_.toAttribute))
  }

  private lazy val toRowConverter: InternalRow => Row = {
    CatalystTypeConverters.createToScalaConverter(metricsSchema)
      .asInstanceOf[InternalRow => Row]
  }

  def collectedMetrics: Row = toRowConverter(accumulator.value)

  override def output: Seq[Attribute] = child.output

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override protected def doExecute(): RDD[InternalRow] = {
    val collector = accumulator
    collector.reset()
    child.execute().mapPartitions { rows =>
      // Only publish the value of the accumulator when the task has completed. This is done by
      // updating a task local accumulator ('updater') which will be merged with the actual
      // accumulator as soon as the task completes. This avoids the following problems during the
      // heartbeat:
      // - Correctness issues due to partially completed/visible updates.
      // - Performance issues due to excessive serialization.
      val updater = collector.copyAndReset()
      TaskContext.get().addTaskCompletionListener[Unit] { _ =>
        collector.setState(updater)
      }

      rows.map { r =>
        updater.add(r)
        r
      }
    }
  }
}

object CollectMetricsExec {
  /**
   * Recursively collect all collected metrics from a query tree.
   */
  def collect(plan: SparkPlan): Map[String, Row] = {
    val metrics = plan.collectInPlanAndSubqueries {
      case collector: CollectMetricsExec => collector.name -> collector.collectedMetrics
    }
    metrics.toMap
  }
}
