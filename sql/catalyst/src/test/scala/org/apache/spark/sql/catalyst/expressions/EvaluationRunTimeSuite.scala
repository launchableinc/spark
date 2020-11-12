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
package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.SparkFunSuite

class EvaluationRunTimeSuite extends SparkFunSuite {

  test("Evaluate ExpressionProxy should create cached result") {
    val runtime = new EvaluationRunTime()
    val proxy = ExpressionProxy(Literal(1), runtime)
    assert(runtime.cache.size() == 0)
    proxy.eval()
    assert(runtime.cache.size() == 1)
    assert(runtime.cache.get(proxy) == ResultProxy(1))
  }

  test("setInput should empty cached result") {
    val runtime = new EvaluationRunTime()
    val proxy1 = ExpressionProxy(Literal(1), runtime)
    assert(runtime.cache.size() == 0)
    proxy1.eval()
    assert(runtime.cache.size() == 1)
    assert(runtime.cache.get(proxy1) == ResultProxy(1))

    val proxy2 = ExpressionProxy(Literal(2), runtime)
    proxy2.eval()
    assert(runtime.cache.size() == 2)
    assert(runtime.cache.get(proxy2) == ResultProxy(2))

    runtime.setInput()
    assert(runtime.cache.size() == 0)
  }

  test("Wrap ExpressionProxy on subexpressions") {
    val runtime = new EvaluationRunTime()

    val one = Literal(1)
    val two = Literal(2)
    val mul = Multiply(one, two)
    val mul2 = Multiply(mul, mul)
    val sqrt = Sqrt(mul2)
    val sum = Add(mul2, sqrt)

    //  ( (one * two) * (one * two) ) + sqrt( (one * two) * (one * two) )
    val proxyExpressions = runtime.proxyExpressions(Seq(sum))
    val proxys = proxyExpressions.flatMap(_.collect {
      case p: ExpressionProxy => p
    })
    // ( (one * two) * (one * two) )
    assert(proxys.size == 1)
    val expected = ExpressionProxy(mul2, runtime)
    assert(proxys.head == expected)
  }

  test("ExpressionProxy won't be on non deterministic") {
    val runtime = new EvaluationRunTime()

    val sum = Add(Rand(0), Rand(0))
    val proxys = runtime.proxyExpressions(Seq(sum, sum)).flatMap(_.collect {
      case p: ExpressionProxy => p
    })
    assert(proxys.isEmpty)
  }
}
