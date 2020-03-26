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

package org.apache.spark.sql.catalyst.util

import java.sql.{Date, Timestamp}
import java.text.SimpleDateFormat
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneId}
import java.util.{Locale, TimeZone}
import java.util.concurrent.TimeUnit

import org.scalatest.Matchers

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.plans.SQLHelper
import org.apache.spark.sql.catalyst.util.DateTimeConstants._
import org.apache.spark.sql.catalyst.util.DateTimeTestUtils._
import org.apache.spark.sql.catalyst.util.DateTimeUtils._
import org.apache.spark.unsafe.types.UTF8String

class DateTimeUtilsSuite extends SparkFunSuite with Matchers with SQLHelper {

  private def defaultZoneId = ZoneId.systemDefault()

  test("nanoseconds truncation") {
    val tf = TimestampFormatter.getFractionFormatter(ZoneId.systemDefault())
    def checkStringToTimestamp(originalTime: String, expectedParsedTime: String): Unit = {
      val parsedTimestampOp = DateTimeUtils.stringToTimestamp(
        UTF8String.fromString(originalTime), defaultZoneId)
      assert(parsedTimestampOp.isDefined, "timestamp with nanoseconds was not parsed correctly")
      assert(DateTimeUtils.timestampToString(tf, parsedTimestampOp.get) === expectedParsedTime)
    }

    checkStringToTimestamp("2015-01-02 00:00:00.123456789", "2015-01-02 00:00:00.123456")
    checkStringToTimestamp("2015-01-02 00:00:00.100000009", "2015-01-02 00:00:00.1")
    checkStringToTimestamp("2015-01-02 00:00:00.000050000", "2015-01-02 00:00:00.00005")
    checkStringToTimestamp("2015-01-02 00:00:00.12005", "2015-01-02 00:00:00.12005")
    checkStringToTimestamp("2015-01-02 00:00:00.100", "2015-01-02 00:00:00.1")
    checkStringToTimestamp("2015-01-02 00:00:00.000456789", "2015-01-02 00:00:00.000456")
    checkStringToTimestamp("1950-01-02 00:00:00.000456789", "1950-01-02 00:00:00.000456")
  }

  test("timestamp and us") {
    val now = new Timestamp(System.currentTimeMillis())
    now.setNanos(1000)
    val ns = fromJavaTimestamp(now)
    assert(ns % 1000000L === 1)
    assert(toJavaTimestamp(ns) === now)

    List(-111111111111L, -1L, 0, 1L, 111111111111L).foreach { t =>
      val ts = toJavaTimestamp(t)
      assert(fromJavaTimestamp(ts) === t)
      assert(toJavaTimestamp(fromJavaTimestamp(ts)) === ts)
    }
  }

  test("us and julian day") {
    val (d, ns) = toJulianDay(0)
    assert(d === JULIAN_DAY_OF_EPOCH)
    assert(ns === 0)
    assert(fromJulianDay(d, ns) == 0L)

    Seq(Timestamp.valueOf("2015-06-11 10:10:10.100"),
      Timestamp.valueOf("2015-06-11 20:10:10.100"),
      Timestamp.valueOf("1900-06-11 20:10:10.100")).foreach { t =>
      val (d, ns) = toJulianDay(fromJavaTimestamp(t))
      assert(ns > 0)
      val t1 = toJavaTimestamp(fromJulianDay(d, ns))
      assert(t.equals(t1))
    }
  }

  test("SPARK-6785: java date conversion before and after epoch") {
    def checkFromToJavaDate(d1: Date): Unit = {
      val d2 = toJavaDate(fromJavaDate(d1))
      assert(d2.toString === d1.toString)
    }

    val df1 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val df2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)

    checkFromToJavaDate(new Date(100))

    checkFromToJavaDate(Date.valueOf("1970-01-01"))

    checkFromToJavaDate(new Date(df1.parse("1970-01-01 00:00:00").getTime))
    checkFromToJavaDate(new Date(df2.parse("1970-01-01 00:00:00 UTC").getTime))

    checkFromToJavaDate(new Date(df1.parse("1970-01-01 00:00:01").getTime))
    checkFromToJavaDate(new Date(df2.parse("1970-01-01 00:00:01 UTC").getTime))

    checkFromToJavaDate(new Date(df1.parse("1969-12-31 23:59:59").getTime))
    checkFromToJavaDate(new Date(df2.parse("1969-12-31 23:59:59 UTC").getTime))

    checkFromToJavaDate(Date.valueOf("1969-01-01"))

    checkFromToJavaDate(new Date(df1.parse("1969-01-01 00:00:00").getTime))
    checkFromToJavaDate(new Date(df2.parse("1969-01-01 00:00:00 UTC").getTime))

    checkFromToJavaDate(new Date(df1.parse("1969-01-01 00:00:01").getTime))
    checkFromToJavaDate(new Date(df2.parse("1969-01-01 00:00:01 UTC").getTime))

    checkFromToJavaDate(new Date(df1.parse("1989-11-09 11:59:59").getTime))
    checkFromToJavaDate(new Date(df2.parse("1989-11-09 19:59:59 UTC").getTime))

    checkFromToJavaDate(new Date(df1.parse("1776-07-04 10:30:00").getTime))
    checkFromToJavaDate(new Date(df2.parse("1776-07-04 18:30:00 UTC").getTime))
  }

  private def toDate(s: String, zoneId: ZoneId = UTC): Option[Int] = {
    stringToDate(UTF8String.fromString(s), zoneId)
  }

  test("string to date") {
    assert(toDate("2015-01-28").get === days(2015, 1, 28))
    assert(toDate("2015").get === days(2015, 1, 1))
    assert(toDate("0001").get === days(1, 1, 1))
    assert(toDate("2015-03").get === days(2015, 3, 1))
    Seq("2015-03-18", "2015-03-18 ", " 2015-03-18", " 2015-03-18 ", "2015-03-18 123142",
      "2015-03-18T123123", "2015-03-18T").foreach { s =>
      assert(toDate(s).get === days(2015, 3, 18))
    }

    assert(toDate("2015-03-18X").isEmpty)
    assert(toDate("2015/03/18").isEmpty)
    assert(toDate("2015.03.18").isEmpty)
    assert(toDate("20150318").isEmpty)
    assert(toDate("2015-031-8").isEmpty)
    assert(toDate("02015-03-18").isEmpty)
    assert(toDate("015-03-18").isEmpty)
    assert(toDate("015").isEmpty)
    assert(toDate("02015").isEmpty)
    assert(toDate("1999 08 01").isEmpty)
    assert(toDate("1999-08 01").isEmpty)
    assert(toDate("1999 08").isEmpty)
  }

  private def toTimestamp(str: String, zoneId: ZoneId): Option[Long] = {
    stringToTimestamp(UTF8String.fromString(str), zoneId)
  }

  test("string to timestamp") {
    for (tz <- ALL_TIMEZONES) {
      def checkStringToTimestamp(str: String, expected: Option[Long]): Unit = {
        assert(toTimestamp(str, tz.toZoneId) === expected)
      }
      val zid = tz.toZoneId

      checkStringToTimestamp("1969-12-31 16:00:00", Option(date(1969, 12, 31, 16, zid = zid)))
      checkStringToTimestamp("0001", Option(date(1, 1, 1, 0, zid = zid)))
      checkStringToTimestamp("2015-03", Option(date(2015, 3, 1, zid = zid)))
      Seq("2015-03-18", "2015-03-18 ", " 2015-03-18", " 2015-03-18 ", "2015-03-18T").foreach { s =>
        checkStringToTimestamp(s, Option(date(2015, 3, 18, zid = zid)))
      }

      var expected = Option(date(2015, 3, 18, 12, 3, 17, zid = zid))
      checkStringToTimestamp("2015-03-18 12:03:17", expected)
      checkStringToTimestamp("2015-03-18T12:03:17", expected)

      // If the string value includes timezone string, it represents the timestamp string
      // in the timezone regardless of the tz parameter.
      var zoneId = getZoneId("-13:53")
      expected = Option(date(2015, 3, 18, 12, 3, 17, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17-13:53", expected)
      checkStringToTimestamp("2015-03-18T12:03:17GMT-13:53", expected)

      expected = Option(date(2015, 3, 18, 12, 3, 17, zid = UTC))
      checkStringToTimestamp("2015-03-18T12:03:17Z", expected)
      checkStringToTimestamp("2015-03-18 12:03:17Z", expected)
      checkStringToTimestamp("2015-03-18 12:03:17UTC", expected)

      zoneId = getZoneId("-01:00")
      expected = Option(date(2015, 3, 18, 12, 3, 17, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17-1:0", expected)
      checkStringToTimestamp("2015-03-18T12:03:17-01:00", expected)
      checkStringToTimestamp("2015-03-18T12:03:17GMT-01:00", expected)

      zoneId = getZoneId("+07:30")
      expected = Option(date(2015, 3, 18, 12, 3, 17, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17+07:30", expected)
      checkStringToTimestamp("2015-03-18T12:03:17 GMT+07:30", expected)

      zoneId = getZoneId("+07:03")
      expected = Option(date(2015, 3, 18, 12, 3, 17, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17+07:03", expected)
      checkStringToTimestamp("2015-03-18T12:03:17GMT+07:03", expected)

      // tests for the string including milliseconds.
      expected = Option(date(2015, 3, 18, 12, 3, 17, 123000, zid = zid))
      checkStringToTimestamp("2015-03-18 12:03:17.123", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123", expected)

      // If the string value includes timezone string, it represents the timestamp string
      // in the timezone regardless of the tz parameter.
      expected = Option(date(2015, 3, 18, 12, 3, 17, 456000, zid = UTC))
      checkStringToTimestamp("2015-03-18T12:03:17.456Z", expected)
      checkStringToTimestamp("2015-03-18 12:03:17.456Z", expected)
      checkStringToTimestamp("2015-03-18 12:03:17.456 UTC", expected)

      zoneId = getZoneId("-01:00")
      expected = Option(date(2015, 3, 18, 12, 3, 17, 123000, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17.123-1:0", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123-01:00", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123 GMT-01:00", expected)

      zoneId = getZoneId("+07:30")
      expected = Option(date(2015, 3, 18, 12, 3, 17, 123000, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17.123+07:30", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123 GMT+07:30", expected)

      zoneId = getZoneId("+07:30")
      expected = Option(date(2015, 3, 18, 12, 3, 17, 123000, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17.123+07:30", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123GMT+07:30", expected)

      expected = Option(date(2015, 3, 18, 12, 3, 17, 123121, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17.123121+7:30", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123121 GMT+0730", expected)

      zoneId = getZoneId("+07:30")
      expected = Option(date(2015, 3, 18, 12, 3, 17, 123120, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17.12312+7:30", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.12312 UT+07:30", expected)

      expected = Option(time(18, 12, 15, zid = zid))
      checkStringToTimestamp("18:12:15", expected)

      zoneId = getZoneId("+07:30")
      expected = Option(time(18, 12, 15, 123120, zid = zoneId))
      checkStringToTimestamp("T18:12:15.12312+7:30", expected)
      checkStringToTimestamp("T18:12:15.12312 UTC+07:30", expected)

      zoneId = getZoneId("+07:30")
      expected = Option(time(18, 12, 15, 123120, zid = zoneId))
      checkStringToTimestamp("18:12:15.12312+7:30", expected)
      checkStringToTimestamp("18:12:15.12312 GMT+07:30", expected)

      expected = Option(date(2011, 5, 6, 7, 8, 9, 100000, zid = zid))
      checkStringToTimestamp("2011-05-06 07:08:09.1000", expected)

      checkStringToTimestamp("238", None)
      checkStringToTimestamp("00238", None)
      checkStringToTimestamp("2015-03-18 123142", None)
      checkStringToTimestamp("2015-03-18T123123", None)
      checkStringToTimestamp("2015-03-18X", None)
      checkStringToTimestamp("2015/03/18", None)
      checkStringToTimestamp("2015.03.18", None)
      checkStringToTimestamp("20150318", None)
      checkStringToTimestamp("2015-031-8", None)
      checkStringToTimestamp("02015-01-18", None)
      checkStringToTimestamp("015-01-18", None)
      checkStringToTimestamp("2015-03-18T12:03.17-20:0", None)
      checkStringToTimestamp("2015-03-18T12:03.17-0:70", None)
      checkStringToTimestamp("2015-03-18T12:03.17-1:0:0", None)
      checkStringToTimestamp("1999 08 01", None)
      checkStringToTimestamp("1999-08 01", None)
      checkStringToTimestamp("1999 08", None)

      // Truncating the fractional seconds
      expected = Option(date(2015, 3, 18, 12, 3, 17, 123456, zid = UTC))
      checkStringToTimestamp("2015-03-18T12:03:17.123456789+0:00", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123456789 UTC+0", expected)
      checkStringToTimestamp("2015-03-18T12:03:17.123456789GMT+00:00", expected)

      zoneId = getZoneId("Europe/Moscow")
      expected = Option(date(2015, 3, 18, 12, 3, 17, 123456, zid = zoneId))
      checkStringToTimestamp("2015-03-18T12:03:17.123456 Europe/Moscow", expected)
    }
  }

  test("SPARK-15379: special invalid date string") {
    // Test stringToDate
    assert(toDate("2015-02-29 00:00:00").isEmpty)
    assert(toDate("2015-04-31 00:00:00").isEmpty)
    assert(toDate("2015-02-29").isEmpty)
    assert(toDate("2015-04-31").isEmpty)


    // Test stringToTimestamp
    assert(stringToTimestamp(
      UTF8String.fromString("2015-02-29 00:00:00"), defaultZoneId).isEmpty)
    assert(stringToTimestamp(
      UTF8String.fromString("2015-04-31 00:00:00"), defaultZoneId).isEmpty)
    assert(toTimestamp("2015-02-29", defaultZoneId).isEmpty)
    assert(toTimestamp("2015-04-31", defaultZoneId).isEmpty)
  }

  test("hours") {
    var input = date(2015, 3, 18, 13, 2, 11, 0, LA)
    assert(getHours(input, LA) === 13)
    assert(getHours(input, UTC) === 20)
    input = date(2015, 12, 8, 2, 7, 9, 0, LA)
    assert(getHours(input, LA) === 2)
    assert(getHours(input, UTC) === 10)
    input = date(10, 1, 1, 0, 0, 0, 0, LA)
    assert(getHours(input, LA) === 0)
  }

  test("minutes") {
    var input = date(2015, 3, 18, 13, 2, 11, 0, LA)
    assert(getMinutes(input, LA) === 2)
    assert(getMinutes(input, UTC) === 2)
    assert(getMinutes(input, getZoneId("Australia/North")) === 32)
    input = date(2015, 3, 8, 2, 7, 9, 0, LA)
    assert(getMinutes(input, LA) === 7)
    assert(getMinutes(input, UTC) === 7)
    assert(getMinutes(input, getZoneId("Australia/North")) === 37)
    input = date(10, 1, 1, 0, 0, 0, 0, LA)
    assert(getMinutes(input, LA) === 0)
  }

  test("seconds") {
    var input = date(2015, 3, 18, 13, 2, 11, 0, LA)
    assert(getSeconds(input, LA) === 11)
    assert(getSeconds(input, UTC) === 11)
    input = date(2015, 3, 8, 2, 7, 9, 0, LA)
    assert(getSeconds(input, LA) === 9)
    assert(getSeconds(input, UTC) === 9)
    input = date(10, 1, 1, 0, 0, 0, 0, LA)
    assert(getSeconds(input, LA) === 0)
  }

  test("hours / minutes / seconds") {
    Seq(Timestamp.valueOf("2015-06-11 10:12:35.789"),
      Timestamp.valueOf("2015-06-11 20:13:40.789"),
      Timestamp.valueOf("1900-06-11 12:14:50.789"),
      Timestamp.valueOf("1700-02-28 12:14:50.123456")).foreach { t =>
      val us = fromJavaTimestamp(t)
      assert(toJavaTimestamp(us) === t)
    }
  }

  test("get day in year") {
    assert(getDayInYear(days(2015, 3, 18)) === 77)
    assert(getDayInYear(days(2012, 3, 18)) === 78)
  }

  test("day of year calculations for old years") {
    assert(getDayInYear(days(1582, 3)) === 60)

    (1000 to 1600 by 10).foreach { year =>
      // January 1 is the 1st day of year.
      assert(getYear(days(year)) === year)
      assert(getMonth(days(year, 1)) === 1)
      assert(getDayInYear(days(year, 1, 1)) === 1)

      // December 31 is the 1st day of year.
      val date = days(year, 12, 31)
      assert(getYear(date) === year)
      assert(getMonth(date) === 12)
      assert(getDayOfMonth(date) === 31)
    }
  }

  test("get year") {
    assert(getYear(days(2015, 2, 18)) === 2015)
    assert(getYear(days(2012, 2, 18)) === 2012)
  }

  test("get quarter") {
    assert(getQuarter(days(2015, 2, 18)) === 1)
    assert(getQuarter(days(2012, 11, 18)) === 4)
  }

  test("get month") {
    assert(getMonth(days(2015, 3, 18)) === 3)
    assert(getMonth(days(2012, 12, 18)) === 12)
  }

  test("get day of month") {
    assert(getDayOfMonth(days(2015, 3, 18)) === 18)
    assert(getDayOfMonth(days(2012, 12, 24)) === 24)
  }

  test("date add months") {
    val input = days(1997, 2, 28, 10, 30)
    assert(dateAddMonths(input, 36) === days(2000, 2, 28))
    assert(dateAddMonths(input, -13) === days(1996, 1, 28))
  }

  test("timestamp add months") {
    val ts1 = date(1997, 2, 28, 10, 30, 0)
    val ts2 = date(2000, 2, 28, 10, 30, 0, 123000)
    assert(timestampAddInterval(ts1, 36, 0, 123000, defaultZoneId) === ts2)

    val ts3 = date(1997, 2, 27, 16, 0, 0, 0, LA)
    val ts4 = date(2000, 2, 27, 16, 0, 0, 123000, LA)
    val ts5 = date(2000, 2, 28, 0, 0, 0, 123000, UTC)
    assert(timestampAddInterval(ts3, 36, 0, 123000, LA) === ts4)
    assert(timestampAddInterval(ts3, 36, 0, 123000, UTC) === ts5)
  }

  test("timestamp add days") {
    // 2019-3-9 is the end of Pacific Standard Time
    val ts1 = date(2019, 3, 9, 12, 0, 0, 123000, LA)
    // 2019-3-10 is the start of Pacific Daylight Time
    val ts2 = date(2019, 3, 10, 12, 0, 0, 123000, LA)
    val ts3 = date(2019, 5, 9, 12, 0, 0, 123000, LA)
    val ts4 = date(2019, 5, 10, 12, 0, 0, 123000, LA)
    // 2019-11-2 is the end of Pacific Daylight Time
    val ts5 = date(2019, 11, 2, 12, 0, 0, 123000, LA)
    // 2019-11-3 is the start of Pacific Standard Time
    val ts6 = date(2019, 11, 3, 12, 0, 0, 123000, LA)

    // transit from Pacific Standard Time to Pacific Daylight Time
    assert(timestampAddInterval(
      ts1, 0, 0, 23 * MICROS_PER_HOUR, LA) === ts2)
    assert(timestampAddInterval(ts1, 0, 1, 0, LA) === ts2)
    // just a normal day
    assert(timestampAddInterval(
      ts3, 0, 0, 24 * MICROS_PER_HOUR, LA) === ts4)
    assert(timestampAddInterval(ts3, 0, 1, 0, LA) === ts4)
    // transit from Pacific Daylight Time to Pacific Standard Time
    assert(timestampAddInterval(
      ts5, 0, 0, 25 * MICROS_PER_HOUR, LA) === ts6)
    assert(timestampAddInterval(ts5, 0, 1, 0, LA) === ts6)
  }

  test("monthsBetween") {
    val date1 = date(1997, 2, 28, 10, 30, 0)
    var date2 = date(1996, 10, 30)
    assert(monthsBetween(date1, date2, true, UTC) === 3.94959677)
    assert(monthsBetween(date1, date2, false, UTC) === 3.9495967741935485)
    Seq(true, false).foreach { roundOff =>
      date2 = date(2000, 2, 28)
      assert(monthsBetween(date1, date2, roundOff, UTC) === -36)
      date2 = date(2000, 2, 29)
      assert(monthsBetween(date1, date2, roundOff, UTC) === -36)
      date2 = date(1996, 3, 31)
      assert(monthsBetween(date1, date2, roundOff, UTC) === 11)
    }

    val date3 = date(2000, 2, 28, 16, zid = LA)
    val date4 = date(1997, 2, 28, 16, zid = LA)
    assert(monthsBetween(date3, date4, true, LA) === 36.0)
    assert(monthsBetween(date3, date4, true, UTC) === 35.90322581)
    assert(monthsBetween(date3, date4, false, UTC) === 35.903225806451616)
  }

  test("from UTC timestamp") {
    def test(utc: String, tz: String, expected: String): Unit = {
      assert(toJavaTimestamp(fromUTCTime(fromJavaTimestamp(Timestamp.valueOf(utc)), tz)).toString
        === expected)
    }
    for (tz <- ALL_TIMEZONES) {
      withDefaultTimeZone(tz) {
        test("2011-12-25 09:00:00.123456", "UTC", "2011-12-25 09:00:00.123456")
        test("2011-12-25 09:00:00.123456", JST.getId, "2011-12-25 18:00:00.123456")
        test("2011-12-25 09:00:00.123456", LA.getId, "2011-12-25 01:00:00.123456")
        test("2011-12-25 09:00:00.123456", "Asia/Shanghai", "2011-12-25 17:00:00.123456")
      }
    }

    withDefaultTimeZone(TimeZone.getTimeZone(LA.getId)) {
      // Daylight Saving Time
      test("2016-03-13 09:59:59.0", LA.getId, "2016-03-13 01:59:59.0")
      test("2016-03-13 10:00:00.0", LA.getId, "2016-03-13 03:00:00.0")
      test("2016-11-06 08:59:59.0", LA.getId, "2016-11-06 01:59:59.0")
      test("2016-11-06 09:00:00.0", LA.getId, "2016-11-06 01:00:00.0")
      test("2016-11-06 10:00:00.0", LA.getId, "2016-11-06 02:00:00.0")
    }
  }

  test("to UTC timestamp") {
    def test(utc: String, tz: String, expected: String): Unit = {
      assert(toJavaTimestamp(toUTCTime(fromJavaTimestamp(Timestamp.valueOf(utc)), tz)).toString
        === expected)
    }

    for (tz <- ALL_TIMEZONES) {
      withDefaultTimeZone(tz) {
        test("2011-12-25 09:00:00.123456", "UTC", "2011-12-25 09:00:00.123456")
        test("2011-12-25 18:00:00.123456", JST.getId, "2011-12-25 09:00:00.123456")
        test("2011-12-25 01:00:00.123456", LA.getId, "2011-12-25 09:00:00.123456")
        test("2011-12-25 17:00:00.123456", "Asia/Shanghai", "2011-12-25 09:00:00.123456")
      }
    }

    val tz = LA.getId
    withDefaultTimeZone(TimeZone.getTimeZone(tz)) {
      // Daylight Saving Time
      test("2016-03-13 01:59:59", tz, "2016-03-13 09:59:59.0")
      test("2016-03-13 02:00:00", tz, "2016-03-13 10:00:00.0")
      test("2016-03-13 03:00:00", tz, "2016-03-13 10:00:00.0")
      test("2016-11-06 00:59:59", tz, "2016-11-06 07:59:59.0")
      test("2016-11-06 01:00:00", tz, "2016-11-06 08:00:00.0")
      test("2016-11-06 01:59:59", tz, "2016-11-06 08:59:59.0")
      test("2016-11-06 02:00:00", tz, "2016-11-06 10:00:00.0")
    }
  }

  test("trailing characters while converting string to timestamp") {
    val s = UTF8String.fromString("2019-10-31T10:59:23Z:::")
    val time = DateTimeUtils.stringToTimestamp(s, defaultZoneId)
    assert(time == None)
  }

  test("truncTimestamp") {
    def testTrunc(
        level: Int,
        expected: String,
        inputTS: Long,
        zoneId: ZoneId = defaultZoneId): Unit = {
      val truncated =
        DateTimeUtils.truncTimestamp(inputTS, level, zoneId)
      val expectedTS = toTimestamp(expected, defaultZoneId)
      assert(truncated === expectedTS.get)
    }

    val defaultInputTS = DateTimeUtils.stringToTimestamp(
      UTF8String.fromString("2015-03-05T09:32:05.359123"), defaultZoneId)
    val defaultInputTS1 = DateTimeUtils.stringToTimestamp(
      UTF8String.fromString("2015-03-31T20:32:05.359"), defaultZoneId)
    val defaultInputTS2 = DateTimeUtils.stringToTimestamp(
      UTF8String.fromString("2015-04-01T02:32:05.359"), defaultZoneId)
    val defaultInputTS3 = DateTimeUtils.stringToTimestamp(
      UTF8String.fromString("2015-03-30T02:32:05.359"), defaultZoneId)
    val defaultInputTS4 = DateTimeUtils.stringToTimestamp(
      UTF8String.fromString("2015-03-29T02:32:05.359"), defaultZoneId)

    testTrunc(DateTimeUtils.TRUNC_TO_YEAR, "2015-01-01T00:00:00", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_MONTH, "2015-03-01T00:00:00", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_DAY, "2015-03-05T00:00:00", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_HOUR, "2015-03-05T09:00:00", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_MINUTE, "2015-03-05T09:32:00", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_SECOND, "2015-03-05T09:32:05", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-02T00:00:00", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-30T00:00:00", defaultInputTS1.get)
    testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-30T00:00:00", defaultInputTS2.get)
    testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-30T00:00:00", defaultInputTS3.get)
    testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-23T00:00:00", defaultInputTS4.get)
    testTrunc(DateTimeUtils.TRUNC_TO_QUARTER, "2015-01-01T00:00:00", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_QUARTER, "2015-01-01T00:00:00", defaultInputTS1.get)
    testTrunc(DateTimeUtils.TRUNC_TO_QUARTER, "2015-04-01T00:00:00", defaultInputTS2.get)
    testTrunc(DateTimeUtils.TRUNC_TO_MICROSECOND, "2015-03-05T09:32:05.359123", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_MILLISECOND, "2015-03-05T09:32:05.359", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_DECADE, "2010-01-01", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_CENTURY, "2001-01-01", defaultInputTS.get)
    testTrunc(DateTimeUtils.TRUNC_TO_MILLENNIUM, "2001-01-01", defaultInputTS.get)

    for (tz <- ALL_TIMEZONES) {
      withDefaultTimeZone(tz) {
        val zid = tz.toZoneId
        val inputTS = DateTimeUtils.stringToTimestamp(
          UTF8String.fromString("2015-03-05T09:32:05.359"), defaultZoneId)
        val inputTS1 = DateTimeUtils.stringToTimestamp(
          UTF8String.fromString("2015-03-31T20:32:05.359"), defaultZoneId)
        val inputTS2 = DateTimeUtils.stringToTimestamp(
          UTF8String.fromString("2015-04-01T02:32:05.359"), defaultZoneId)
        val inputTS3 = DateTimeUtils.stringToTimestamp(
          UTF8String.fromString("2015-03-30T02:32:05.359"), defaultZoneId)
        val inputTS4 = DateTimeUtils.stringToTimestamp(
          UTF8String.fromString("2015-03-29T02:32:05.359"), defaultZoneId)
        val inputTS5 = DateTimeUtils.stringToTimestamp(
          UTF8String.fromString("1999-03-29T01:02:03.456789"), defaultZoneId)

        testTrunc(DateTimeUtils.TRUNC_TO_YEAR, "2015-01-01T00:00:00", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_MONTH, "2015-03-01T00:00:00", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_DAY, "2015-03-05T00:00:00", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_HOUR, "2015-03-05T09:00:00", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_MINUTE, "2015-03-05T09:32:00", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_SECOND, "2015-03-05T09:32:05", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-02T00:00:00", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-30T00:00:00", inputTS1.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-30T00:00:00", inputTS2.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-30T00:00:00", inputTS3.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_WEEK, "2015-03-23T00:00:00", inputTS4.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_QUARTER, "2015-01-01T00:00:00", inputTS.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_QUARTER, "2015-01-01T00:00:00", inputTS1.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_QUARTER, "2015-04-01T00:00:00", inputTS2.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_DECADE, "1990-01-01", inputTS5.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_CENTURY, "1901-01-01", inputTS5.get, zid)
        testTrunc(DateTimeUtils.TRUNC_TO_MILLENNIUM, "2001-01-01", inputTS.get, zid)
      }
    }
  }

  test("daysToMicros and microsToDays") {
    val input = date(2015, 12, 31, 16, zid = LA)
    assert(microsToDays(input, LA) === 16800)
    assert(microsToDays(input, UTC) === 16801)
    assert(microsToDays(-1 * MILLIS_PER_DAY + 1, UTC) == -1)

    var expected = date(2015, 12, 31, zid = LA)
    assert(daysToMicros(16800, LA) === expected)

    expected = date(2015, 12, 31, zid = UTC)
    assert(daysToMicros(16800, UTC) === expected)

    // There are some days are skipped entirely in some timezone, skip them here.
    val skipped_days = Map[String, Set[Int]](
      "Kwajalein" -> Set(8632, 8633, 8634),
      "Pacific/Apia" -> Set(15338),
      "Pacific/Enderbury" -> Set(9130, 9131),
      "Pacific/Fakaofo" -> Set(15338),
      "Pacific/Kiritimati" -> Set(9130, 9131),
      "Pacific/Kwajalein" -> Set(8632, 8633, 8634),
      MIT.getId -> Set(15338))
    for (tz <- ALL_TIMEZONES) {
      val skipped = skipped_days.getOrElse(tz.getID, Set.empty)
      val testingData = Seq(-20000, 20000) ++
        (1 to 1000).map(_ => (math.random() * 40000 - 20000).toInt)
      testingData.foreach { d =>
        if (!skipped.contains(d)) {
          assert(microsToDays(daysToMicros(d, tz.toZoneId), tz.toZoneId) === d,
            s"Round trip of $d did not work in tz $tz")
        }
      }
    }
  }

  test("microsToMillis") {
    assert(DateTimeUtils.microsToMillis(-9223372036844776001L) === -9223372036844777L)
    assert(DateTimeUtils.microsToMillis(-157700927876544L) === -157700927877L)
  }

  test("special timestamp values") {
    testSpecialDatetimeValues { zoneId =>
      val tolerance = TimeUnit.SECONDS.toMicros(30)

      assert(toTimestamp("Epoch", zoneId).get === 0)
      val now = instantToMicros(Instant.now())
      toTimestamp("NOW", zoneId).get should be(now +- tolerance)
      assert(toTimestamp("now UTC", zoneId) === None)
      val localToday = LocalDateTime.now(zoneId)
        .`with`(LocalTime.MIDNIGHT)
        .atZone(zoneId)
      val yesterday = instantToMicros(localToday.minusDays(1).toInstant)
      toTimestamp(" Yesterday", zoneId).get should be(yesterday +- tolerance)
      val today = instantToMicros(localToday.toInstant)
      toTimestamp("Today ", zoneId).get should be(today +- tolerance)
      val tomorrow = instantToMicros(localToday.plusDays(1).toInstant)
      toTimestamp(" tomorrow CET ", zoneId).get should be(tomorrow +- tolerance)
    }
  }

  test("special date values") {
    testSpecialDatetimeValues { zoneId =>
      assert(toDate("epoch", zoneId).get === 0)
      val today = localDateToDays(LocalDate.now(zoneId))
      assert(toDate("YESTERDAY", zoneId).get === today - 1)
      assert(toDate(" Now ", zoneId).get === today)
      assert(toDate("now UTC", zoneId) === None) // "now" does not accept time zones
      assert(toDate("today", zoneId).get === today)
      assert(toDate("tomorrow CET ", zoneId).get === today + 1)
    }
  }

  private def parseToJulianMicros(s: String): Long = {
    val ts = Timestamp.valueOf(s)
    val julianMicros = millisToMicros(ts.getTime) +
      ((ts.getNanos / NANOS_PER_MICROS) % MICROS_PER_MILLIS)
    julianMicros
  }

  private def parseToGregMicros(s: String, zoneId: ZoneId): Long = {
    instantToMicros(LocalDateTime.parse(s).atZone(zoneId).toInstant)
  }

  test("rebase julian to/from gregorian micros") {
    outstandingTimezones.foreach { timeZone =>
      withDefaultTimeZone(timeZone) {
        Seq(
          "0001-01-01 01:02:03.654321",
          "1000-01-01 03:02:01.123456",
          "1582-10-04 00:00:00.000000",
          "1582-10-15 00:00:00.999999", // Gregorian cutover day
          "1883-11-10 00:00:00.000000", // America/Los_Angeles -7:52:58 zone offset
          "1883-11-20 00:00:00.000000", // America/Los_Angeles -08:00 zone offset
          "1969-12-31 11:22:33.000100",
          "1970-01-01 00:00:00.000001", // The epoch day
          "2020-03-14 09:33:01.500000").foreach { ts =>
          withClue(s"time zone = ${timeZone.getID} ts = $ts") {
            val julianMicros = parseToJulianMicros(ts)
            val gregMicros = parseToGregMicros(ts.replace(' ', 'T'), timeZone.toZoneId)

            assert(rebaseJulianToGregorianMicros(julianMicros) === gregMicros)
            assert(rebaseGregorianToJulianMicros(gregMicros) === julianMicros)
          }
        }
      }
    }
  }

  // millisToDays() and fromJavaDate() are taken from Spark 2.4
  private def millisToDaysLegacy(millisUtc: Long, timeZone: TimeZone): Int = {
    val millisLocal = millisUtc + timeZone.getOffset(millisUtc)
    Math.floor(millisLocal.toDouble / MILLIS_PER_DAY).toInt
  }
  private def fromJavaDateLegacy(date: Date): Int = {
    millisToDaysLegacy(date.getTime, TimeZone.getDefault)
  }

  test("rebase gregorian to/from julian days") {
    outstandingTimezones.foreach { timeZone =>
      withDefaultTimeZone(timeZone) {
        Seq(
          "0001-01-01",
          "1000-01-01",
          "1582-10-04",
          "1582-10-15", // Gregorian cutover day
          "1883-11-10", // America/Los_Angeles -7:52:58 zone offset
          "1883-11-20", // America/Los_Angeles -08:00 zone offset
          "1969-12-31",
          "1970-01-01", // The epoch day
          "2020-03-14").foreach { date =>
          val julianDays = fromJavaDateLegacy(Date.valueOf(date))
          val gregorianDays = localDateToDays(LocalDate.parse(date))

          assert(rebaseGregorianToJulianDays(gregorianDays) === julianDays)
          assert(rebaseJulianToGregorianDays(julianDays) === gregorianDays)
        }
      }
    }
  }

  test("rebase julian to gregorian date for leap years") {
    outstandingTimezones.foreach { timeZone =>
      withDefaultTimeZone(timeZone) {
        Seq(
          "1000-02-29" -> "1000-03-01",
          "1600-02-29" -> "1600-02-29",
          "1700-02-29" -> "1700-03-01",
          "2000-02-29" -> "2000-02-29").foreach { case (julianDate, gregDate) =>
          withClue(s"tz = ${timeZone.getID} julian date = $julianDate greg date = $gregDate") {
            val date = Date.valueOf(julianDate)
            val julianDays = fromJavaDateLegacy(date)
            val gregorianDays = localDateToDays(LocalDate.parse(gregDate))

            assert(rebaseJulianToGregorianDays(julianDays) === gregorianDays)
          }
        }
      }
    }
  }

  test("rebase julian to gregorian timestamp for leap years") {
    outstandingTimezones.foreach { timeZone =>
      withDefaultTimeZone(timeZone) {
        Seq(
          "1000-02-29 01:02:03.123456" -> "1000-03-01T01:02:03.123456",
          "1600-02-29 11:12:13.654321" -> "1600-02-29T11:12:13.654321",
          "1700-02-29 21:22:23.000001" -> "1700-03-01T21:22:23.000001",
          "2000-02-29 00:00:00.999999" -> "2000-02-29T00:00:00.999999"
        ).foreach { case (julianTs, gregTs) =>
          withClue(s"tz = ${timeZone.getID} julian ts = $julianTs greg ts = $gregTs") {
            val julianMicros = parseToJulianMicros(julianTs)
            val gregorianMicros = parseToGregMicros(gregTs, timeZone.toZoneId)

            assert(rebaseJulianToGregorianMicros(julianMicros) === gregorianMicros)
          }
        }
      }
    }
  }
}
