/**
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
package org.apache.hadoop.mapreduce.checkpoint;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/**
 * CheckpointService提供一个简单的api去存储和找回一个任务的状态
 * The CheckpointService provides a simple API to store and retrieve the state of a task.
 *
 * Checkpoints检查点是原子的，单编写器，一次写入，多读取器，现成的许多类型的对象。
 * 通过仅在提交检查点时才释放检查点的CheckpointID，并通过防止为写入而重新打开检查点来提供此功能。
 * Checkpoints are atomic, single-writer, write-once, multiple-readers,
 * ready-many type of objects. This is provided by releasing the CheckpointID
 * for a checkpoint only upon commit of the checkpoint, and by preventing a
 * checkpoint to be re-opened for writes.
 *
 * 诸如持久性，可用性，压缩，垃圾收集，配额之类的非功能属性留给实现。
 * Non-functional properties such as durability, availability, compression,
 * garbage collection, quotas are left to the implementation.
 *
 *该API被设想为检查点服务的基本构建块，
 *在其上可以分层丰富的接口（例如，框架提供
 *对象序列化，检查点元数据和出处等）
 *
 */
public interface CheckpointService {

  //checkpoint写入通道
  public interface CheckpointWriteChannel extends WritableByteChannel { }
  //checkpoint读取通道
  public interface CheckpointReadChannel extends ReadableByteChannel { }

  /**
   * 这个方法创建一个checkpoint和提供一个管道取写入它，checkpoint的名称和位置是未知的对于这个时间的用户来说，实际上，
   * 在调用commit之前，不会将CheckpointID释放给用户。这使得强制执行写入原子变得容易。
   * This method creates a checkpoint and provide a channel to write to it. The
   * name/location of the checkpoint are unknown to the user as of this time, in
   * fact, the CheckpointID is not released to the user until commit is called.
   * This makes enforcing atomicity of writes easy.
   * @return a channel that can be used to write to the checkpoint 一个管道能够被用于写入这个checkpoint
   * @throws IOException
   * @throws InterruptedException
   */
  public CheckpointWriteChannel create()
    throws IOException, InterruptedException;

  /**
   * 用于完成和现有检查点。它返回CheckpointID，可用于访问（只读）检查点。
   * 这是保证checkpoint的原子性
   * Used to finalize and existing checkpoint. It returns the CheckpointID that
   * can be later used to access (read-only) this checkpoint. This guarantees
   * atomicity of the checkpoint.
   * @param ch the CheckpointWriteChannel to commit
   * @return a CheckpointID
   * @throws IOException
   * @throws InterruptedException
   */
  public CheckpointID commit(CheckpointWriteChannel ch)
    throws IOException, InterruptedException;

  /**
   * 2次提交，它将中止当前的检查点。 垃圾收集的选择留给实现。 CheckpointID不会生成也不会释放给用户，因此无法访问该Checkpoint。
   * Dual to commit, it aborts the current checkpoint. Garbage collection
   * choices are left to the implementation. The CheckpointID is not generated
   * nor released to the user so the checkpoint is not accessible.
   * @param ch the CheckpointWriteChannel to abort
   * @throws IOException
   * @throws InterruptedException
   */
  public void abort(CheckpointWriteChannel ch)
      throws IOException, InterruptedException;

  /**
   * 提供一个CheckpointID返回一个读取通道
   * Given a CheckpointID returns a reading channel.
   * @param id CheckpointID for the checkpoint to be opened
   * @return a CheckpointReadChannel
   * @throws IOException
   * @throws InterruptedException
   */
  public CheckpointReadChannel open(CheckpointID id)
    throws IOException, InterruptedException;

  /**
   * 它丢弃一个存在的检查点定义通过对给定的CheckpointID
   * It discards an existing checkpoint identified by its CheckpointID.
   * @param  id CheckpointID for the checkpoint to be deleted
   * @return a boolean confirming success of the deletion
   * @throws IOException
   * @throws InterruptedException
   */
  public boolean delete(CheckpointID id)
    throws IOException, InterruptedException;

}
