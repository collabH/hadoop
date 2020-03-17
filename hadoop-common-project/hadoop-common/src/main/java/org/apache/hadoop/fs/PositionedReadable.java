/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

import java.io.EOFException;
import java.io.IOException;

/**
 * 允许位置读取的流。
 * Stream that permits positional reading.
 * <p>
 * Implementations are required to implement thread-safe operations; this may
 * be supported by concurrent access to the data, or by using a synchronization
 * mechanism to serialize access.
 * <p>
 * Not all implementations meet this requirement. Those that do not cannot
 * be used as a backing store for some applications, such as Apache HBase.
 * <p>
 * Independent of whether or not they are thread safe, some implementations
 * may make the intermediate state of the system, specifically the position
 * obtained in {@code Seekable.getPos()} visible.
 */
@InterfaceAudience.Public
@InterfaceStability.Evolving
public interface PositionedReadable {
    /**
     * 从文件的指定position读取最多length的数据并存入缓冲区buffer的指定偏离量offset区，返回实际读到的字节数。
     * Read up to the specified number of bytes, from a given
     * position within a file, and return the number of bytes read. This does not
     * change the current offset of a file, and is thread-safe.
     *
     * <i>Warning: Not all filesystems satisfy the thread-safety requirement.</i>
     *
     * @param position position within file
     * @param buffer   destination buffer
     * @param offset   offset in the buffer
     * @param length   number of bytes to read
     * @return actual number of bytes read; -1 means "none" 有可能小于指定length长度
     * @throws IOException IO problems.
     */
    int read(long position, byte[] buffer, int offset, int length)
            throws IOException;

    /**
     * Read the specified number of bytes, from a given
     * position within a file. This does not
     * change the current offset of a file, and is thread-safe.
     * 从文件中给定的位置读取指定的字节数。 不会更改文件的当前偏移量，并且是线程安全的。
     *
     * @param position position within file
     * @param buffer   destination buffer
     * @param offset   offset in the buffer
     * @param length   number of bytes to read
     * @throws IOException  IO problems.
     * @throws EOFException the end of the data was reached before
     *                      the read operation completed
     */
    void readFully(long position, byte[] buffer, int offset, int length)
            throws IOException;

    /**
     * Read number of bytes equal to the length of the buffer, from a given
     * position within a file. This does not
     * change the current offset of a file, and is thread-safe.
     *
     * <i>Warning: Not all filesystems satisfy the thread-safety requirement.</i>
     *
     * @param position position within file
     * @param buffer   destination buffer
     * @throws IOException  IO problems.
     * @throws EOFException the end of the data was reached before
     *                      the read operation completed
     */
    void readFully(long position, byte[] buffer) throws IOException;
}
