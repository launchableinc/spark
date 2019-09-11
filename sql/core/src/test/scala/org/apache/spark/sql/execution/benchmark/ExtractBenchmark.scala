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

package org.apache.spark.sql.execution.benchmark

import java.time.Instant

/**
 * Synthetic benchmark for the extract function.
 * To run this benchmark:
 * {{{
 *   1. without sbt:
 *      bin/spark-submit --class <this class> --jars <spark core test jar> <sql core test jar>
 *   2. build/sbt "sql/test:runMain <this class>"
 *   3. generate result:
 *      SPARK_GENERATE_BENCHMARK_FILES=1 build/sbt "sql/test:runMain <this class>"
 *      Results will be written to "benchmarks/ExtractBenchmark-results.txt".
 * }}}
 */
object ExtractBenchmark extends SqlBasedBenchmark {
  private def doBenchmark(cardinality: Long, exprs: String*): Unit = {
    val sinceSecond = Instant.parse("2010-01-01T00:00:00Z").getEpochSecond
    spark
      .range(sinceSecond, sinceSecond + cardinality, 1, 1)
      .selectExpr(exprs: _*)
      .write
      .format("noop")
      .save()
  }

  private def run(cardinality: Long, name: String, exprs: String*): Unit = {
    codegenBenchmark(name, cardinality) {
      doBenchmark(cardinality, exprs: _*)
    }
  }

  private def castExpr(from: String): String = from match {
    case "timestamp" => s"cast(id as $from)"
    // Use modulo division to prevent overflow in internal conversion to microseconds
    case "date" => s"cast((id % 10000) as $from)"
    case other => throw new IllegalArgumentException(
      s"Unsupported column type $other. Valid column types are 'timestamp' and 'date'")
  }

  private def run(func: String, cardinality: Long, field: String, from: String): Unit = {
    val expr = func match {
      case "extract" => s"EXTRACT($field FROM ${castExpr(from)})"
      case "date_part" => s"DATE_PART('$field', ${castExpr(from)})"
      case other => throw new IllegalArgumentException(
        s"Unsupported function '$other'. Valid functions are 'extract' and 'date_part'.")
    }
    codegenBenchmark(s"$field of $from", cardinality) {
      doBenchmark(cardinality, expr)
    }
  }

  override def runBenchmarkSuite(mainArgs: Array[String]): Unit = {
    val N = 10000000L
    val fields = Seq(
      "MILLENNIUM", "CENTURY", "DECADE", "YEAR",
      "ISOYEAR", "QUARTER", "MONTH", "WEEK",
      "DAY", "DAYOFWEEK", "DOW", "ISODOW",
      "DOY", "HOUR", "MINUTE", "SECOND",
      "MILLISECONDS", "MICROSECONDS", "EPOCH")

    Seq("extract", "date_part").foreach { func =>
      Seq("timestamp", "date").foreach { dateType =>
        runBenchmark(s"Invoke $func for $dateType") {
          run(N, s"cast to $dateType", castExpr(dateType))
          fields.foreach(run(func, N, _, dateType))
        }
      }
    }
  }
}
