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
package org.apache.spark.network.crypto;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.apache.commons.crypto.cipher.CryptoCipher;
import org.apache.commons.crypto.stream.CryptoInputStream;
import org.apache.commons.crypto.stream.CryptoOutputStream;
import org.apache.commons.crypto.stream.input.ChannelInput;
import org.apache.spark.network.util.MapConfigProvider;
import org.apache.spark.network.util.TransportConf;
import org.hamcrest.CoreMatchers;
import org.junit.Test;

import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransportCipherTest {

  @Test
  public void testBufferNotLeaksOnInternalError() throws IOException {
    String algorithm = "TestAlgorithm";
    TransportConf conf = new TransportConf("Test", MapConfigProvider.EMPTY);
    TransportCipher cipher = new TransportCipher(conf.cryptoConf(), conf.cipherTransformation(),
            new SecretKeySpec(new byte[256], algorithm), new byte[0], new byte[0]) {

      @Override
      CryptoOutputStream createOutputStream(WritableByteChannel ch) {
        return null;
      }

      @Override
      CryptoInputStream createInputStream(ReadableByteChannel ch) throws IOException {
        CryptoCipher cipher = mock(CryptoCipher.class);
        when(cipher.getBlockSize()).thenReturn(8);
        when(cipher.getAlgorithm()).thenReturn(algorithm);

        return new CryptoInputStream(new ChannelInput(ch), cipher, 1024,
                null, new IvParameterSpec(new byte[0])) {
          @Override
          public int read(ByteBuffer dst) {
            // Simulate throwing InternalError
            // CHECKSTYLE:OFF
            throw new InternalError();
            // CHECKSTYLE:ON
          }

          @Override
          public int read(byte[] b, int off, int len) {
            // Simulate throwing InternalError
            // CHECKSTYLE:OFF
            throw new InternalError();
            // CHECKSTYLE:ON
          }
        };
      }
    };

    EmbeddedChannel channel = new EmbeddedChannel();
    cipher.addToChannel(channel);

    ByteBuf buffer = Unpooled.wrappedBuffer(new byte[] { 1, 2 });
    ByteBuf buffer2 = Unpooled.wrappedBuffer(new byte[] { 1, 2 });

    try {
      channel.writeInbound(buffer);
      fail("Should have raised InternalError");
    } catch (InternalError expected) {
      // expected
      assertEquals(1, buffer.refCnt());
    }

    try {
      channel.writeInbound(buffer2);
      fail("Should have raised an exception");
    } catch (Throwable expected) {
      assertThat(expected, CoreMatchers.instanceOf(IOException.class));
      assertEquals(0, buffer2.refCnt());
    }

    // Simulate closing the connection
    assertFalse(channel.finish());

    // Buffer was released on close.
    assertEquals(0, buffer.refCnt());
  }
}
