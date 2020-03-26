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

package org.apache.hadoop.mapred;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.util.ReflectionUtils;

/** Default {@link MapRunnable} implementation.*/

/**
 * 默认的MapRunnable实现
 * @param <K1>
 * @param <V1>
 * @param <K2>
 * @param <V2>
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class MapRunner<K1, V1, K2, V2>
    implements MapRunnable<K1, V1, K2, V2> {
  
  private Mapper<K1, V1, K2, V2> mapper;
  //是否开启增量计数
  private boolean incrProcCount;

  @SuppressWarnings("unchecked")
  public void configure(JobConf job) {
    //反射得到mapper类型
    this.mapper = ReflectionUtils.newInstance(job.getMapperClass(), job);
    //increment processed counter only if skipping feature is enabled
    //仅当启用了跳过功能时，递增处理的计数器
    this.incrProcCount = SkipBadRecords.getMapperMaxSkipRecords(job)>0 &&
      SkipBadRecords.getAutoIncrMapperProcCount(job);
  }

  public void run(RecordReader<K1, V1> input, OutputCollector<K2, V2> output,
                  Reporter reporter)
    throws IOException {
    try {
      // allocate key & value instances that are re-used for all entries
      K1 key = input.createKey();
      V1 value = input.createValue();
      //是否能够读取下一行
      while (input.next(key, value)) {
        // map pair to output
        //丢入mapper函数
        mapper.map(key, value, output, reporter);
        if(incrProcCount) {
          reporter.incrCounter(SkipBadRecords.COUNTER_GROUP, 
              SkipBadRecords.COUNTER_MAP_PROCESSED_RECORDS, 1);
        }
      }
    } finally {
      mapper.close();
    }
  }

  protected Mapper<K1, V1, K2, V2> getMapper() {
    return mapper;
  }
}
