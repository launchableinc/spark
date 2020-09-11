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

package org.apache.spark.sql.execution;

import java.io.IOException;

import scala.collection.Iterator;

import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.UnsafeRow;

// Base class for UnsafeExternalRowSorter and UnsafeExternalRowWindowSorter
public abstract class UnsafeExternalRowSorterBase {
  public abstract void insertRow(UnsafeRow row) throws IOException;
  public abstract Iterator<InternalRow> sort() throws IOException;
  public abstract Iterator<InternalRow> sort(Iterator<UnsafeRow> inputIterator) throws IOException;
  public abstract long getPeakMemoryUsage();
  public abstract long getSortTimeNanos();
  public abstract void cleanupResources();
  abstract void setTestSpillFrequency(int frequency);

  public Iterator<InternalRow> getIterator() throws IOException {
    throw new IOException("This method is not implmented.");
  }
}
