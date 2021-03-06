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
package org.apache.hadoop.mapreduce;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.QueueACL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.mapred.QueueManager.toFullPropertyName;

import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.apache.hadoop.mapreduce.protocol.ClientProtocol;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.split.JobSplitWriter;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.util.JsonSerialization;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.yarn.api.records.ReservationId;

import com.google.common.base.Charsets;

@InterfaceAudience.Private
@InterfaceStability.Unstable
class JobSubmitter {
  protected static final Logger LOG =
      LoggerFactory.getLogger(JobSubmitter.class);
  private static final String SHUFFLE_KEYGEN_ALGORITHM = "HmacSHA1";
  private static final int SHUFFLE_KEY_LENGTH = 64;
  private FileSystem jtFs;
  //rpc客户端
  private ClientProtocol submitClient;
  private String submitHostName;
  private String submitHostAddress;

  JobSubmitter(FileSystem submitFs, ClientProtocol submitClient)
  throws IOException {
    this.submitClient = submitClient;
    this.jtFs = submitFs;
  }

  /**
   * configure the jobconf of the user with the command line options of
   * -libjars, -files, -archives.
   * @param job
   * @throws IOException
   */
  private void copyAndConfigureFiles(Job job, Path jobSubmitDir)
  throws IOException {
    Configuration conf = job.getConfiguration();
    //
    boolean useWildcards = conf.getBoolean(Job.USE_WILDCARD_FOR_LIBJARS,
        Job.DEFAULT_USE_WILDCARD_FOR_LIBJARS);
    // 创建job资源上传器
    JobResourceUploader rUploader = new JobResourceUploader(jtFs, useWildcards);

    // 上传资源信息
    rUploader.uploadResources(job, jobSubmitDir);

    // Get the working directory. If not set, sets it to filesystem working dir
    // This code has been added so that working directory reset before running
    // the job. This is necessary for backward compatibility as other systems
    // might use the public API JobConf#setWorkingDirectory to reset the working
    // directory. 得到工作目录
    job.getWorkingDirectory();
  }

  /**
   * Internal method for submitting jobs to the system.
   *
   * <p>The job submission process involves:
   * <ol>
   *   <li>
   *   Checking the input and output specifications of the job.
   *   </li>
   *   <li>
   *   Computing the {@link InputSplit}s for the job.
   *   </li>
   *   <li>
   *   Setup the requisite accounting information for the
   *   {@link DistributedCache} of the job, if necessary.
   *   </li>
   *   <li>
   *   Copying the job's jar and configuration to the map-reduce system
   *   directory on the distributed file-system.
   *   </li>
   *   <li>
   *   Submitting the job to the <code>JobTracker</code> and optionally
   *   monitoring it's status.
   *   </li>
   * </ol></p>
   * @param job the configuration to submit
   * @param cluster the handle to the Cluster
   * @throws ClassNotFoundException
   * @throws InterruptedException
   * @throws IOException
   */
  JobStatus submitJobInternal(Job job, Cluster cluster)
  throws ClassNotFoundException, InterruptedException, IOException {

    //validate the jobs output specs
    //校验job的输出
    checkSpecs(job);

    Configuration conf = job.getConfiguration();

    // 将配置添加至MR的分布式缓存中
    addMRFrameworkToDistributedCache(conf);
    //得到任务暂存区域路径，临时的数据会存放在这个路径
    Path jobStagingArea = JobSubmissionFiles.getStagingDir(cluster, conf);
    //configure the command line options correctly on the submitting dfs
    //在提交的dfs上正确配置命令行选项
    //得到本地套接字信息
    InetAddress ip = InetAddress.getLocalHost();
    if (ip != null) {
      submitHostAddress = ip.getHostAddress();
      submitHostName = ip.getHostName();
      conf.set(MRJobConfig.JOB_SUBMITHOST,submitHostName);
      conf.set(MRJobConfig.JOB_SUBMITHOSTADDR,submitHostAddress);
    }
    //得到一个新的jobId
    JobID jobId = submitClient.getNewJobID();
    job.setJobID(jobId);
    //创建提交的job目录路径，先写道临时区
    Path submitJobDir = new Path(jobStagingArea, jobId.toString());
    JobStatus status = null;
    // 设置配置用户名，mapreduce任务输出临时目录
    try {
      conf.set(MRJobConfig.USER_NAME,
          UserGroupInformation.getCurrentUser().getShortUserName());
      conf.set("hadoop.http.filter.initializers",
          "org.apache.hadoop.yarn.server.webproxy.amfilter.AmFilterInitializer");
      conf.set(MRJobConfig.MAPREDUCE_JOB_DIR, submitJobDir.toString());
      LOG.debug("Configuring job " + jobId + " with " + submitJobDir
          + " as the submit dir");
      // get delegation token for the dir
      TokenCache.obtainTokensForNamenodes(job.getCredentials(),
          new Path[] { submitJobDir }, conf);

      populateTokenCache(conf, job.getCredentials());

      // generate a secret to authenticate shuffle transfers
      if (TokenCache.getShuffleSecretKey(job.getCredentials()) == null) {
        KeyGenerator keyGen;
        try {
          keyGen = KeyGenerator.getInstance(SHUFFLE_KEYGEN_ALGORITHM);
          keyGen.init(SHUFFLE_KEY_LENGTH);
        } catch (NoSuchAlgorithmException e) {
          throw new IOException("Error generating shuffle secret key", e);
        }
        SecretKey shuffleKey = keyGen.generateKey();
        TokenCache.setShuffleSecretKey(shuffleKey.getEncoded(),
            job.getCredentials());
      }
      if (CryptoUtils.isEncryptedSpillEnabled(conf)) {
        conf.setInt(MRJobConfig.MR_AM_MAX_ATTEMPTS, 1);
        LOG.warn("Max job attempts set to 1 since encrypted intermediate" +
                "data spill is enabled");
      }

      // 复制和配置文件
      copyAndConfigureFiles(job, submitJobDir);

      // 拿到提交作业的文件路径
      Path submitJobFile = JobSubmissionFiles.getJobConfPath(submitJobDir);

      // Create the splits for the job
      LOG.debug("Creating splits at " + jtFs.makeQualified(submitJobDir));
      // 写分片
      int maps = writeSplits(job, submitJobDir);
      // 设置mapreduce.job.maps个数
      conf.setInt(MRJobConfig.NUM_MAPS, maps);
      LOG.info("number of splits:" + maps);

      // 获取配置的最大maptask个数
      int maxMaps = conf.getInt(MRJobConfig.JOB_MAX_MAP,
          MRJobConfig.DEFAULT_JOB_MAX_MAP);
      if (maxMaps >= 0 && maxMaps < maps) {
        throw new IllegalArgumentException("The number of map tasks " + maps +
            " exceeded limit " + maxMaps);
      }

      // write "queue admins of the queue to which job is being submitted"
      // to job file.得到队列名称
      String queue = conf.get(MRJobConfig.QUEUE_NAME,
          JobConf.DEFAULT_QUEUE_NAME);
      AccessControlList acl = submitClient.getQueueAdmins(queue);
      conf.set(toFullPropertyName(queue,
          QueueACL.ADMINISTER_JOBS.getAclName()), acl.getAclString());

      // removing jobtoken referrals before copying the jobconf to HDFS
      // as the tasks don't need this setting, actually they may break
      // because of it if present as the referral will point to a
      // different job.
      TokenCache.cleanUpTokenReferral(conf);

      if (conf.getBoolean(
          MRJobConfig.JOB_TOKEN_TRACKING_IDS_ENABLED,
          MRJobConfig.DEFAULT_JOB_TOKEN_TRACKING_IDS_ENABLED)) {
        // Add HDFS tracking ids
        ArrayList<String> trackingIds = new ArrayList<String>();
        for (Token<? extends TokenIdentifier> t :
            job.getCredentials().getAllTokens()) {
          trackingIds.add(t.decodeIdentifier().getTrackingId());
        }
        conf.setStrings(MRJobConfig.JOB_TOKEN_TRACKING_IDS,
            trackingIds.toArray(new String[trackingIds.size()]));
      }

      // Set reservation info if it exists
      ReservationId reservationId = job.getReservationId();
      if (reservationId != null) {
        conf.set(MRJobConfig.RESERVATION_ID, reservationId.toString());
      }

      // Write job file to submit dir
      writeConf(conf, submitJobFile);

      //
      // Now, actually submit the job (using the submit name)
      //
      printTokens(jobId, job.getCredentials());
      // 提交任务
      status = submitClient.submitJob(
          jobId, submitJobDir.toString(), job.getCredentials());
      if (status != null) {
        return status;
      } else {
        throw new IOException("Could not launch job");
      }
    } finally {
      if (status == null) {
        LOG.info("Cleaning up the staging area " + submitJobDir);
        if (jtFs != null && submitJobDir != null)
          jtFs.delete(submitJobDir, true);

      }
    }
  }

  /**
   * 校验job的specs
   * @param job
   * @throws ClassNotFoundException
   * @throws InterruptedException
   * @throws IOException
   */
  private void checkSpecs(Job job) throws ClassNotFoundException,
      InterruptedException, IOException {
    JobConf jConf = (JobConf)job.getConfiguration();
    // Check the output specification，先上兼容
    if (jConf.getNumReduceTasks() == 0 ?
        jConf.getUseNewMapper() : jConf.getUseNewReducer()) {
      //根据反射拿到job输出类型，这也是为什么需要mr指定类型的原因，因为反射会将反型类型擦除
      org.apache.hadoop.mapreduce.OutputFormat<?, ?> output =
        ReflectionUtils.newInstance(job.getOutputFormatClass(),
          job.getConfiguration());
      // 校验输出地址空间
      output.checkOutputSpecs(job);
    } else {
      jConf.getOutputFormat().checkOutputSpecs(jtFs, jConf);
    }
  }

    /**
     * 写配置文件
     * @param conf
     * @param jobFile
     * @throws IOException
     */
  private void writeConf(Configuration conf, Path jobFile)
      throws IOException {
    // Write job file to JobTracker's fs 写入job文件到JobTracker的文件系统中
    FSDataOutputStream out =
      FileSystem.create(jtFs, jobFile,
                        new FsPermission(JobSubmissionFiles.JOB_FILE_PERMISSION));
    try {
        // 写job.xml
      conf.writeXml(out);
    } finally {
      out.close();
    }
  }

  private void printTokens(JobID jobId,
      Credentials credentials) throws IOException {
    LOG.info("Submitting tokens for job: " + jobId);
    LOG.info("Executing with tokens: {}", credentials.getAllTokens());
  }

  @SuppressWarnings("unchecked")
  private <T extends InputSplit>
  int writeNewSplits(JobContext job, Path jobSubmitDir) throws IOException,
      InterruptedException, ClassNotFoundException {
    Configuration conf = job.getConfiguration();
    // 反射从配置中拿到输入类型
    InputFormat<?, ?> input =
      ReflectionUtils.newInstance(job.getInputFormatClass(), conf);

    // 客户端分片
    List<InputSplit> splits = input.getSplits(job);
    //创建分片数组
    T[] array = (T[]) splits.toArray(new InputSplit[splits.size()]);

    // sort the splits into order based on size, so that the biggest
    // go first，分片排序
    Arrays.sort(array, new SplitComparator());
    // 创建分片文件
    JobSplitWriter.createSplitFiles(jobSubmitDir, conf,
        jobSubmitDir.getFileSystem(conf), array);
    return array.length;
  }

  /**
   * 客户端分片
   * @param job
   * @param jobSubmitDir
   * @return
   * @throws IOException
   * @throws InterruptedException
   * @throws ClassNotFoundException
   */
  private int writeSplits(org.apache.hadoop.mapreduce.JobContext job,
      Path jobSubmitDir) throws IOException,
      InterruptedException, ClassNotFoundException {
    JobConf jConf = (JobConf)job.getConfiguration();
    // 根据分片文件数得到对应maptask个数
    int maps;
    // 向下兼容
    if (jConf.getUseNewMapper()) {
      maps = writeNewSplits(job, jobSubmitDir);
    } else {
      maps = writeOldSplits(jConf, jobSubmitDir);
    }
    return maps;
  }

  //method to write splits for old api mapper.
  private int writeOldSplits(JobConf job, Path jobSubmitDir)
  throws IOException {
    org.apache.hadoop.mapred.InputSplit[] splits =
    job.getInputFormat().getSplits(job, job.getNumMapTasks());
    // sort the splits into order based on size, so that the biggest
    // go first
    Arrays.sort(splits, new Comparator<org.apache.hadoop.mapred.InputSplit>() {
      public int compare(org.apache.hadoop.mapred.InputSplit a,
                         org.apache.hadoop.mapred.InputSplit b) {
        try {
          long left = a.getLength();
          long right = b.getLength();
          if (left == right) {
            return 0;
          } else if (left < right) {
            return 1;
          } else {
            return -1;
          }
        } catch (IOException ie) {
          throw new RuntimeException("Problem getting input split size", ie);
        }
      }
    });
    JobSplitWriter.createSplitFiles(jobSubmitDir, job,
        jobSubmitDir.getFileSystem(job), splits);
    return splits.length;
  }

  private static class SplitComparator implements Comparator<InputSplit> {
    @Override
    public int compare(InputSplit o1, InputSplit o2) {
      try {
        long len1 = o1.getLength();
        long len2 = o2.getLength();
        if (len1 < len2) {
          return 1;
        } else if (len1 == len2) {
          return 0;
        } else {
          return -1;
        }
      } catch (IOException ie) {
        throw new RuntimeException("exception in compare", ie);
      } catch (InterruptedException ie) {
        throw new RuntimeException("exception in compare", ie);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void readTokensFromFiles(Configuration conf, Credentials credentials)
  throws IOException {
    // add tokens and secrets coming from a token storage file
    String binaryTokenFilename =
      conf.get(MRJobConfig.MAPREDUCE_JOB_CREDENTIALS_BINARY);
    if (binaryTokenFilename != null) {
      Credentials binary = Credentials.readTokenStorageFile(
          FileSystem.getLocal(conf).makeQualified(
              new Path(binaryTokenFilename)),
          conf);
      credentials.addAll(binary);
    }
    // add secret keys coming from a json file
    String tokensFileName = conf.get("mapreduce.job.credentials.json");
    if(tokensFileName != null) {
      LOG.info("loading user's secret keys from " + tokensFileName);
      String localFileName = new Path(tokensFileName).toUri().getPath();

      try {
        // read JSON
        Map<String, String> nm = JsonSerialization.mapReader().readValue(
            new File(localFileName));

        for(Map.Entry<String, String> ent: nm.entrySet()) {
          credentials.addSecretKey(new Text(ent.getKey()), ent.getValue()
              .getBytes(Charsets.UTF_8));
        }
      } catch (JsonMappingException | JsonParseException e) {
        LOG.warn("couldn't parse Token Cache JSON file with user secret keys");
      }
    }
  }

  //get secret keys and tokens and store them into TokenCache
  private void populateTokenCache(Configuration conf, Credentials credentials)
  throws IOException{
    readTokensFromFiles(conf, credentials);
    // add the delegation tokens from configuration
    String [] nameNodes = conf.getStrings(MRJobConfig.JOB_NAMENODES);
    LOG.debug("adding the following namenodes' delegation tokens:" +
        Arrays.toString(nameNodes));
    if(nameNodes != null) {
      Path [] ps = new Path[nameNodes.length];
      for(int i=0; i< nameNodes.length; i++) {
        ps[i] = new Path(nameNodes[i]);
      }
      TokenCache.obtainTokensForNamenodes(credentials, ps, conf);
    }
  }

  /**
   * 添加MapReduce框架到分布式缓存
   * @param conf
   * @throws IOException
   */
  @SuppressWarnings("deprecation")
  private static void addMRFrameworkToDistributedCache(Configuration conf)
      throws IOException {
    //拿到mapreduce框架路径
    String framework =
        conf.get(MRJobConfig.MAPREDUCE_APPLICATION_FRAMEWORK_PATH, "");
    if (!framework.isEmpty()) {
      URI uri;
      try {
        uri = new URI(framework);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("Unable to parse '" + framework
            + "' as a URI, check the setting for "
            + MRJobConfig.MAPREDUCE_APPLICATION_FRAMEWORK_PATH, e);
      }
      //得到链接名称
      String linkedName = uri.getFragment();

      // resolve any symlinks in the URI path so using a "current" symlink
      // to point to a specific version shows the specific version
      // in the distributed cache configuration
      //根据路径和文件得到对于的文件系统
      FileSystem fs = FileSystem.get(uri, conf);
      //限定使用此FileSystem的路径，如果是相对的，则将设为绝对路径
      Path frameworkPath = fs.makeQualified(
          new Path(uri.getScheme(), uri.getAuthority(), uri.getPath()));
      //拿到文件上下文
      FileContext fc = FileContext.getFileContext(frameworkPath.toUri(), conf);
      //解析路径
      frameworkPath = fc.resolvePath(frameworkPath);
      uri = frameworkPath.toUri();
      try {
        uri = new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
            null, linkedName);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException(e);
      }

      //配置加入分布式缓存中
      DistributedCache.addCacheArchive(uri, conf);
    }
  }
}
