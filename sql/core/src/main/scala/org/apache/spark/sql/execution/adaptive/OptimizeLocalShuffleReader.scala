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

package org.apache.spark.sql.execution.adaptive

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.physical.{Partitioning, UnknownPartitioning}
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}
import org.apache.spark.sql.execution.exchange.{BroadcastExchangeExec, EnsureRequirements, ShuffleExchangeExec}
import org.apache.spark.sql.execution.joins.BroadcastHashJoinExec
import org.apache.spark.sql.internal.SQLConf

case class OptimizeLocalShuffleReader(
    conf: SQLConf,
    var parent: Option[SparkPlan] = None) extends Rule[SparkPlan] {

  def canUseLocalShuffleReader(plan: SparkPlan): Boolean = {
    ShuffleQueryStageExec.isShuffleQueryStageExec(plan)
  }

  override def apply(plan: SparkPlan): SparkPlan = {
    if (!conf.getConf(SQLConf.OPTIMIZE_LOCAL_SHUFFLE_READER_ENABLED)) {
      return plan
    }
    // In order to choose the best optimal plan after applied local reader rule.
    // When `BroadcastExchangeExec` + `ShuffleQueryStageExec` occurs,
    // we also make the shuffle reader to local reader.
    val newPlan = if (canUseLocalShuffleReader(plan) &&
      parent.nonEmpty && parent.get.isInstanceOf[BroadcastExchangeExec]) {
      LocalShuffleReaderExec(plan.asInstanceOf[QueryStageExec])
    } else plan

    val optimizedPlan = newPlan.transformDown {
      case join: BroadcastHashJoinExec =>
        val optimizedRightPlan = if (canUseLocalShuffleReader(join.right)) {
          LocalShuffleReaderExec(join.right.asInstanceOf[QueryStageExec])
        } else join.right
        val optimizedLeftPlan = if (canUseLocalShuffleReader(join.left)) {
          LocalShuffleReaderExec(join.left.asInstanceOf[QueryStageExec])
        } else join.left
        join.copy(left = optimizedLeftPlan, right = optimizedRightPlan)
    }

    def numExchanges(plan: SparkPlan): Int = {
      plan.collect {
        case e: ShuffleExchangeExec => e
      }.length
    }

    val numExchangeBefore = numExchanges(EnsureRequirements(conf).apply(plan))
    val numExchangeAfter = numExchanges(EnsureRequirements(conf).apply(optimizedPlan))
    if (numExchangeAfter > numExchangeBefore) {
      logDebug("OptimizeLocalShuffleReader rule is not applied due" +
        " to additional shuffles will be introduced.")
      plan
    } else {
      optimizedPlan
    }
  }
}

case class LocalShuffleReaderExec(child: QueryStageExec) extends UnaryExecNode {

  override def output: Seq[Attribute] = child.output

  override def doCanonicalize(): SparkPlan = child.canonicalized

  override def outputPartitioning: Partitioning = {

    def tryReserveChildPartitioning(stage: ShuffleQueryStageExec): Partitioning = {
      val initialPartitioning = stage.plan.child.outputPartitioning
      if (initialPartitioning.isInstanceOf[UnknownPartitioning]) {
        UnknownPartitioning(stage.plan.shuffleDependency.rdd.partitions.length)
      } else {
        initialPartitioning
      }
    }

    child match {
      case stage: ShuffleQueryStageExec =>
        tryReserveChildPartitioning(stage)
      case ReusedQueryStageExec(_, stage: ShuffleQueryStageExec, _) =>
        tryReserveChildPartitioning(stage)
    }
  }

  private var cachedShuffleRDD: RDD[InternalRow] = null

  override protected def doExecute(): RDD[InternalRow] = {
    if (cachedShuffleRDD == null) {
      cachedShuffleRDD = child match {
        case stage: ShuffleQueryStageExec =>
          stage.plan.createLocalShuffleRDD()
        case ReusedQueryStageExec(_, stage: ShuffleQueryStageExec, _) =>
          stage.plan.createLocalShuffleRDD()
      }
    }
    cachedShuffleRDD
  }
}
