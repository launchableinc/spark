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

import org.apache.spark.SparkException
import org.apache.spark.sql.catalyst.CatalystTypeConverters.{createToCatalystConverter, createToScalaConverter, isPrimitive}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.codegen._
import org.apache.spark.sql.catalyst.expressions.codegen.Block._
import org.apache.spark.sql.types.{AbstractDataType, AnyDataType, DataType}

/**
 * User-defined function.
 * @param function  The user defined scala function to run.
 *                  Note that if you use primitive parameters, you are not able to check if it is
 *                  null or not, and the UDF will return null for you if the primitive input is
 *                  null. Use boxed type or [[Option]] if you wanna do the null-handling yourself.
 * @param dataType  Return type of function.
 * @param children  The input expressions of this UDF.
 * @param inputEncoders ExpressionEncoder for each input parameters. For a input parameter which
 *                      serialized as struct will use encoder instead of CatalystTypeConverters to
 *                      convert internal value to Scala value.
 * @param udfName  The user-specified name of this UDF.
 * @param nullable  True if the UDF can return null value.
 * @param udfDeterministic  True if the UDF is deterministic. Deterministic UDF returns same result
 *                          each time it is invoked with a particular input.
 */
case class ScalaUDF(
    function: AnyRef,
    dataType: DataType,
    children: Seq[Expression],
    inputEncoders: Seq[Option[ExpressionEncoder[_]]] = Nil,
    udfName: Option[String] = None,
    nullable: Boolean = true,
    udfDeterministic: Boolean = true)
  extends Expression with NonSQLExpression with UserDefinedExpression {

  override lazy val deterministic: Boolean = udfDeterministic && children.forall(_.deterministic)

  override def toString: String = s"${udfName.getOrElse("UDF")}(${children.mkString(", ")})"

  /**
   * The analyzer should be aware of Scala primitive types so as to make the
   * UDF return null if there is any null input value of these types. On the
   * other hand, Java UDFs can only have boxed types, thus this will return
   * Nil(has same effect with all false) and analyzer will skip null-handling
   * on them.
   */
  def inputPrimitives: Seq[Boolean] = {
    inputEncoders.map { encoderOpt =>
      // It's possible that some of the inputs don't have a specific encoder(e.g. `Any`)
      if (encoderOpt.isDefined) {
        val encoder = encoderOpt.get
        if (encoder.isSerializedAsStruct) {
          // struct type is not primitive
          false
        } else {
          // `nullable` is false iff the type is primitive
          !encoder.schema.head.nullable
        }
      } else {
        // Any type is not primitive
        false
      }
    }
  }

  /**
   * The expected input types of this UDF, used to perform type coercion. If we do
   * not want to perform coercion, simply use "Nil". Note that it would've been
   * better to use Option of Seq[DataType] so we can use "None" as the case for no
   * type coercion. However, that would require more refactoring of the codebase.
   */
  def inputTypes: Seq[AbstractDataType] = {
    inputEncoders.map { encoderOpt =>
      if (encoderOpt.isDefined) {
        val encoder = encoderOpt.get
        if (encoder.isSerializedAsStruct) {
          encoder.schema
        } else {
          encoder.schema.head.dataType
        }
      } else {
        AnyDataType
      }
    }
  }

  private def scalaConverter(i: Int, dataType: DataType): Any => Any = {
    if (inputEncoders.isEmpty) {
      // for untyped Scala UDF
      createToScalaConverter(dataType)
    } else if (isPrimitive(dataType)) {
      identity
    } else {
      val encoder = inputEncoders(i)
      encoder match {
        case Some(enc) =>
          val fromRow = enc.resolveAndBind().createDeserializer()
          if (enc.isSerializedAsStructForTopLevel) {
            row: Any => fromRow(row.asInstanceOf[InternalRow])
          } else {
            value: Any =>
              val row = new GenericInternalRow(1)
              row.update(0, value)
              fromRow(row)
          }

        case None => createToScalaConverter(dataType)
      }
    }
  }

  // scalastyle:off line.size.limit

  /** This method has been generated by this script

    (1 to 22).map { x =>
      val anys = (1 to x).map(x => "Any").reduce(_ + ", " + _)
      val childs = (0 to x - 1).map(x => s"val child$x = children($x)").reduce(_ + "\n  " + _)
      val converters = (0 to x - 1).map(x => s"lazy val converter$x = scalaConverter($x, child$x.dataType)").reduce(_ + "\n  " + _)
      val evals = (0 to x - 1).map(x => s"converter$x(child$x.eval(input))").reduce(_ + ",\n      " + _)

      s"""case $x =>
      val func = function.asInstanceOf[($anys) => Any]
      $childs
      $converters
      (input: InternalRow) => {
        func(
          $evals)
      }
      """
    }.foreach(println)

  */
  private[this] val f = children.size match {
    case 0 =>
      val func = function.asInstanceOf[() => Any]
      (input: InternalRow) => {
        func()
      }

    case 1 =>
      val func = function.asInstanceOf[(Any) => Any]
      val child0 = children(0)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)))
      }

    case 2 =>
      val func = function.asInstanceOf[(Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)))
      }

    case 3 =>
      val func = function.asInstanceOf[(Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)))
      }

    case 4 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)))
      }

    case 5 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)))
      }

    case 6 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)))
      }

    case 7 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)))
      }

    case 8 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)))
      }

    case 9 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)))
      }

    case 10 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)))
      }

    case 11 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)))
      }

    case 12 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)))
      }

    case 13 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)))
      }

    case 14 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)))
      }

    case 15 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)))
      }

    case 16 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      val child15 = children(15)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      lazy val converter15 = scalaConverter(15, child15.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)),
          converter15(child15.eval(input)))
      }

    case 17 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      val child15 = children(15)
      val child16 = children(16)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      lazy val converter15 = scalaConverter(15, child15.dataType)
      lazy val converter16 = scalaConverter(16, child16.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)),
          converter15(child15.eval(input)),
          converter16(child16.eval(input)))
      }

    case 18 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      val child15 = children(15)
      val child16 = children(16)
      val child17 = children(17)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      lazy val converter15 = scalaConverter(15, child15.dataType)
      lazy val converter16 = scalaConverter(16, child16.dataType)
      lazy val converter17 = scalaConverter(17, child17.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)),
          converter15(child15.eval(input)),
          converter16(child16.eval(input)),
          converter17(child17.eval(input)))
      }

    case 19 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      val child15 = children(15)
      val child16 = children(16)
      val child17 = children(17)
      val child18 = children(18)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      lazy val converter15 = scalaConverter(15, child15.dataType)
      lazy val converter16 = scalaConverter(16, child16.dataType)
      lazy val converter17 = scalaConverter(17, child17.dataType)
      lazy val converter18 = scalaConverter(18, child18.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)),
          converter15(child15.eval(input)),
          converter16(child16.eval(input)),
          converter17(child17.eval(input)),
          converter18(child18.eval(input)))
      }

    case 20 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      val child15 = children(15)
      val child16 = children(16)
      val child17 = children(17)
      val child18 = children(18)
      val child19 = children(19)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      lazy val converter15 = scalaConverter(15, child15.dataType)
      lazy val converter16 = scalaConverter(16, child16.dataType)
      lazy val converter17 = scalaConverter(17, child17.dataType)
      lazy val converter18 = scalaConverter(18, child18.dataType)
      lazy val converter19 = scalaConverter(19, child19.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)),
          converter15(child15.eval(input)),
          converter16(child16.eval(input)),
          converter17(child17.eval(input)),
          converter18(child18.eval(input)),
          converter19(child19.eval(input)))
      }

    case 21 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      val child15 = children(15)
      val child16 = children(16)
      val child17 = children(17)
      val child18 = children(18)
      val child19 = children(19)
      val child20 = children(20)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      lazy val converter15 = scalaConverter(15, child15.dataType)
      lazy val converter16 = scalaConverter(16, child16.dataType)
      lazy val converter17 = scalaConverter(17, child17.dataType)
      lazy val converter18 = scalaConverter(18, child18.dataType)
      lazy val converter19 = scalaConverter(19, child19.dataType)
      lazy val converter20 = scalaConverter(20, child20.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)),
          converter15(child15.eval(input)),
          converter16(child16.eval(input)),
          converter17(child17.eval(input)),
          converter18(child18.eval(input)),
          converter19(child19.eval(input)),
          converter20(child20.eval(input)))
      }

    case 22 =>
      val func = function.asInstanceOf[(Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any, Any) => Any]
      val child0 = children(0)
      val child1 = children(1)
      val child2 = children(2)
      val child3 = children(3)
      val child4 = children(4)
      val child5 = children(5)
      val child6 = children(6)
      val child7 = children(7)
      val child8 = children(8)
      val child9 = children(9)
      val child10 = children(10)
      val child11 = children(11)
      val child12 = children(12)
      val child13 = children(13)
      val child14 = children(14)
      val child15 = children(15)
      val child16 = children(16)
      val child17 = children(17)
      val child18 = children(18)
      val child19 = children(19)
      val child20 = children(20)
      val child21 = children(21)
      lazy val converter0 = scalaConverter(0, child0.dataType)
      lazy val converter1 = scalaConverter(1, child1.dataType)
      lazy val converter2 = scalaConverter(2, child2.dataType)
      lazy val converter3 = scalaConverter(3, child3.dataType)
      lazy val converter4 = scalaConverter(4, child4.dataType)
      lazy val converter5 = scalaConverter(5, child5.dataType)
      lazy val converter6 = scalaConverter(6, child6.dataType)
      lazy val converter7 = scalaConverter(7, child7.dataType)
      lazy val converter8 = scalaConverter(8, child8.dataType)
      lazy val converter9 = scalaConverter(9, child9.dataType)
      lazy val converter10 = scalaConverter(10, child10.dataType)
      lazy val converter11 = scalaConverter(11, child11.dataType)
      lazy val converter12 = scalaConverter(12, child12.dataType)
      lazy val converter13 = scalaConverter(13, child13.dataType)
      lazy val converter14 = scalaConverter(14, child14.dataType)
      lazy val converter15 = scalaConverter(15, child15.dataType)
      lazy val converter16 = scalaConverter(16, child16.dataType)
      lazy val converter17 = scalaConverter(17, child17.dataType)
      lazy val converter18 = scalaConverter(18, child18.dataType)
      lazy val converter19 = scalaConverter(19, child19.dataType)
      lazy val converter20 = scalaConverter(20, child20.dataType)
      lazy val converter21 = scalaConverter(21, child21.dataType)
      (input: InternalRow) => {
        func(
          converter0(child0.eval(input)),
          converter1(child1.eval(input)),
          converter2(child2.eval(input)),
          converter3(child3.eval(input)),
          converter4(child4.eval(input)),
          converter5(child5.eval(input)),
          converter6(child6.eval(input)),
          converter7(child7.eval(input)),
          converter8(child8.eval(input)),
          converter9(child9.eval(input)),
          converter10(child10.eval(input)),
          converter11(child11.eval(input)),
          converter12(child12.eval(input)),
          converter13(child13.eval(input)),
          converter14(child14.eval(input)),
          converter15(child15.eval(input)),
          converter16(child16.eval(input)),
          converter17(child17.eval(input)),
          converter18(child18.eval(input)),
          converter19(child19.eval(input)),
          converter20(child20.eval(input)),
          converter21(child21.eval(input)))
      }
  }

  // scalastyle:on line.size.limit
  override def doGenCode(
      ctx: CodegenContext,
      ev: ExprCode): ExprCode = {
    val converterClassName = classOf[Any => Any].getName

    // The type converters for inputs and the result.
    val converters: Array[Any => Any] = children.zipWithIndex.map { case (c, i) =>
      scalaConverter(i, c.dataType)
    }.toArray :+ createToCatalystConverter(dataType)
    val convertersTerm = ctx.addReferenceObj("converters", converters, s"$converterClassName[]")
    val errorMsgTerm = ctx.addReferenceObj("errMsg", udfErrorMessage)
    val resultTerm = ctx.freshName("result")

    // codegen for children expressions
    val evals = children.map(_.genCode(ctx))

    // Generate the codes for expressions and calling user-defined function
    // We need to get the boxedType of dataType's javaType here. Because for the dataType
    // such as IntegerType, its javaType is `int` and the returned type of user-defined
    // function is Object. Trying to convert an Object to `int` will cause casting exception.
    val evalCode = evals.map(_.code).mkString("\n")
    val (funcArgs, initArgs) = evals.zipWithIndex.zip(children.map(_.dataType)).map {
      case ((eval, i), dt) =>
        val argTerm = ctx.freshName("arg")
        val initArg = if (isPrimitive(dt)) {
          val convertedTerm = ctx.freshName("conv")
          s"""
             |${CodeGenerator.boxedType(dt)} $convertedTerm = ${eval.value};
             |Object $argTerm = ${eval.isNull} ? null : $convertedTerm;
           """.stripMargin
        } else {
          s"Object $argTerm = ${eval.isNull} ? null : $convertersTerm[$i].apply(${eval.value});"
        }
        (argTerm, initArg)
    }.unzip

    val udf = ctx.addReferenceObj("udf", function, s"scala.Function${children.length}")
    val getFuncResult = s"$udf.apply(${funcArgs.mkString(", ")})"
    val resultConverter = s"$convertersTerm[${children.length}]"
    val boxedType = CodeGenerator.boxedType(dataType)

    val funcInvokation = if (isPrimitive(dataType)
        // If the output is nullable, the returned value must be unwrapped from the Option
        && !nullable) {
      s"$resultTerm = ($boxedType)$getFuncResult"
    } else {
      s"$resultTerm = ($boxedType)$resultConverter.apply($getFuncResult)"
    }
    val callFunc =
      s"""
         |$boxedType $resultTerm = null;
         |try {
         |  $funcInvokation;
         |} catch (Exception e) {
         |  throw new org.apache.spark.SparkException($errorMsgTerm, e);
         |}
       """.stripMargin

    ev.copy(code =
      code"""
         |$evalCode
         |${initArgs.mkString("\n")}
         |$callFunc
         |
         |boolean ${ev.isNull} = $resultTerm == null;
         |${CodeGenerator.javaType(dataType)} ${ev.value} = ${CodeGenerator.defaultValue(dataType)};
         |if (!${ev.isNull}) {
         |  ${ev.value} = $resultTerm;
         |}
       """.stripMargin)
  }

  private[this] val resultConverter = createToCatalystConverter(dataType)

  lazy val udfErrorMessage = {
    val funcCls = function.getClass.getSimpleName
    val inputTypes = children.map(_.dataType.catalogString).mkString(", ")
    val outputType = dataType.catalogString
    s"Failed to execute user defined function($funcCls: ($inputTypes) => $outputType)"
  }

  override def eval(input: InternalRow): Any = {
    val result = try {
      f(input)
    } catch {
      case e: Exception =>
        throw new SparkException(udfErrorMessage, e)
    }

    resultConverter(result)
  }
}
