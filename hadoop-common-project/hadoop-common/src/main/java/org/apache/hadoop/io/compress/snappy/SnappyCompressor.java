/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.io.compress.snappy;

import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.util.NativeCodeLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * snappy压缩器
 * A {@link Compressor} based on the snappy compression algorithm.
 * http://code.google.com/p/snappy/
 */
public class SnappyCompressor implements Compressor {
  private static final Logger LOG =
      LoggerFactory.getLogger(SnappyCompressor.class.getName());
  //默认直接缓存区大小
  private static final int DEFAULT_DIRECT_BUFFER_SIZE = 64 * 1024;

  //直接缓冲区大小
  private int directBufferSize;
  // 压缩直接缓存区
  private Buffer compressedDirectBuf = null;
  //为压缩直接缓存区长度
  private int uncompressedDirectBufLen;
  private Buffer uncompressedDirectBuf = null;
  //保存setInput()
  private byte[] userBuf = null;
  private int userBufOff = 0, userBufLen = 0;
  //是否还有为处理的数据标志已经是否进行压缩读取标志
  private boolean finish, finished;

  //数据读取计数器
  private long bytesRead = 0L;
  //数据压缩写入计数器
  private long bytesWritten = 0L;

  private static boolean nativeSnappyLoaded = false;
  
  static {
    if (NativeCodeLoader.isNativeCodeLoaded() &&
        NativeCodeLoader.buildSupportsSnappy()) {
      try {
        initIDs();
        nativeSnappyLoaded = true;
      } catch (Throwable t) {
        LOG.error("failed to load SnappyCompressor", t);
      }
    }
  }
  
  public static boolean isNativeCodeLoaded() {
    return nativeSnappyLoaded;
  }
  
  /**
   * Creates a new compressor.
   *
   * @param directBufferSize size of the direct buffer to be used.
   */
  public SnappyCompressor(int directBufferSize) {
    this.directBufferSize = directBufferSize;

    uncompressedDirectBuf = ByteBuffer.allocateDirect(directBufferSize);
    compressedDirectBuf = ByteBuffer.allocateDirect(directBufferSize);
    compressedDirectBuf.position(directBufferSize);
  }

  /**
   * Creates a new compressor with the default buffer size.
   */
  public SnappyCompressor() {
    this(DEFAULT_DIRECT_BUFFER_SIZE);
  }

  /**
   * Sets input data for compression.
   * This should be called whenever #needsInput() returns
   * <code>true</code> indicating that more input data is required.
   *
   * @param b   Input data
   * @param off Start offset
   * @param len Length
   */
  @Override
  public void setInput(byte[] b, int off, int len) {
    if (b == null) {
      throw new NullPointerException();
    }
    if (off < 0 || len < 0 || off > b.length - len) {
      throw new ArrayIndexOutOfBoundsException();
    }
    //设置是否存在可以读取的压缩数据
    finished = false;

    //如果田间数据大于uncompressedDirectBuf剩余容量
    if (len > uncompressedDirectBuf.remaining()) {
      // save data; now !needsInput
      //将数据计入userBuf、userBufOff、userBufLen中
      this.userBuf = b;
      this.userBufOff = off;
      this.userBufLen = len;
    } else {
      ((ByteBuffer) uncompressedDirectBuf).put(b, off, len);
      uncompressedDirectBufLen = uncompressedDirectBuf.position();
    }

    bytesRead += len;
  }

  /**
   * If a write would exceed the capacity of the direct buffers, it is set
   * aside to be loaded by this function while the compressed data are
   * consumed.
   */
  void setInputFromSavedData() {
    if (0 >= userBufLen) {
      return;
    }
    finished = false;

    uncompressedDirectBufLen = Math.min(userBufLen, directBufferSize);
    ((ByteBuffer) uncompressedDirectBuf).put(userBuf, userBufOff,
        uncompressedDirectBufLen);

    // Note how much data is being fed to snappy
    userBufOff += uncompressedDirectBufLen;
    userBufLen -= uncompressedDirectBufLen;
  }

  /**
   * Does nothing.
   */
  @Override
  public void setDictionary(byte[] b, int off, int len) {
    // do nothing
  }

  /**
   * Returns true if the input data buffer is empty and
   * #setInput() should be called to provide more input.
   *
   * @return <code>true</code> if the input data buffer is empty and
   *         #setInput() should be called in order to provide more input.
   */
  @Override
  public boolean needsInput() {
    //输出缓冲区有未读取的数据，输入缓冲区没有空间，已经压缩器已经借用外部缓冲区，这是需要通过compress取走已经压缩的数据
    return !(compressedDirectBuf.remaining() > 0
        || uncompressedDirectBuf.remaining() == 0 || userBufLen > 0);
  }

  /**
   * When called, indicates that compression should end
   * with the current contents of the input buffer.
   */
  @Override
  public void finish() {
    finish = true;
  }

  /**
   * Returns true if the end of the compressed
   * data output stream has been reached.
   *
   * @return <code>true</code> if the end of the compressed
   *         data output stream has been reached.
   */
  @Override
  public boolean finished() {
    //如果finsh为true并且finished为ture并且输出缓冲区没有数据是finished为true
    // Check if all uncompressed data has been consumed
    return (finish && finished && compressedDirectBuf.remaining() == 0);
  }

  /**
   * Fills specified buffer with compressed data. Returns actual number
   * of bytes of compressed data. A return value of 0 indicates that
   * needsInput() should be called in order to determine if more input
   * data is required.
   *
   * @param b   Buffer for the compressed data
   * @param off Start offset of the data
   * @param len Size of the buffer
   * @return The actual number of bytes of compressed data.
   */
  @Override
  public int compress(byte[] b, int off, int len)
      throws IOException {
    if (b == null) {
      throw new NullPointerException();
    }
    if (off < 0 || len < 0 || off > b.length - len) {
      throw new ArrayIndexOutOfBoundsException();
    }

    //校验输出缓冲区容量
    // Check if there is compressed data
    int n = compressedDirectBuf.remaining();
    //输出缓冲区还有数据
    if (n > 0) {
      n = Math.min(n, len);
      ((ByteBuffer) compressedDirectBuf).get(b, off, n);
      bytesWritten += n;
      return n;
    }
    //初始化输出缓冲区
    // Re-initialize the snappy's output direct-buffer
    compressedDirectBuf.clear();
    compressedDirectBuf.limit(0);
    //如果输入缓冲区没有数据
    if (0 == uncompressedDirectBuf.position()) {
      // No compressed data, so we should have !needsInput or !finished
      // 处理写入userBuf中的数据
      setInputFromSavedData();
      //二次判断
      if (0 == uncompressedDirectBuf.position()) {
        // Called without data; write nothing
        // finished为true没有需要写入数据
        finished = true;
        return 0;
      }
    }

    // Compress data 压缩数据
    n = compressBytesDirect();
    //放入压缩输出缓冲区
    compressedDirectBuf.limit(n);
    uncompressedDirectBuf.clear(); // snappy consumes all buffer input

    // Set 'finished' if snapy has consumed all user-data
    if (0 == userBufLen) {
      finished = true;
    }

    // Get atmost 'len' bytes
    n = Math.min(n, len);
    bytesWritten += n;
    ((ByteBuffer) compressedDirectBuf).get(b, off, n);

    return n;
  }

  /**
   * Resets compressor so that a new set of input data can be processed.
   */
  @Override
  public void reset() {
    finish = false;
    finished = false;
    uncompressedDirectBuf.clear();
    uncompressedDirectBufLen = 0;
    compressedDirectBuf.clear();
    compressedDirectBuf.limit(0);
    userBufOff = userBufLen = 0;
    bytesRead = bytesWritten = 0L;
  }

  /**
   * Prepare the compressor to be used in a new stream with settings defined in
   * the given Configuration
   *
   * @param conf Configuration from which new setting are fetched
   */
  @Override
  public void reinit(Configuration conf) {
    reset();
  }

  /**
   * Return number of bytes given to this compressor since last reset.
   */
  @Override
  public long getBytesRead() {
    return bytesRead;
  }

  /**
   * Return number of bytes consumed by callers of compress since last reset.
   */
  @Override
  public long getBytesWritten() {
    return bytesWritten;
  }

  /**
   * Closes the compressor and discards any unprocessed input.
   */
  @Override
  public void end() {
  }

  private native static void initIDs();

  private native int compressBytesDirect();

  public native static String getLibraryName();
}
