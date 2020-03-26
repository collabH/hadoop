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
package org.apache.hadoop.ha;

import java.io.IOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.ha.HAServiceProtocol.HAServiceState;
import org.apache.hadoop.ha.HAServiceProtocol.StateChangeRequestInfo;
import org.apache.hadoop.ha.HAServiceProtocol.RequestSource;
import org.apache.hadoop.ipc.RPC;

import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * 所述FailOverController是负责在启动时选举活动的服务时，或者当当前的活动正在改变（例如由于故障），监控服务的运行状况，
 * 并进行故障切换时一个新的有效服务是或者由用户手动选择或当选
 *
 * The FailOverController is responsible for electing an active service
 * on startup or when the current active is changing (eg due to failure),
 * monitoring the health of a service, and performing a fail-over when a
 * new active service is either manually selected by a user or elected.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class FailoverController {

  private static final Logger LOG =
      LoggerFactory.getLogger(FailoverController.class);

  //优雅的超时
  private final int gracefulFenceTimeout;
  //新活跃的rpc超时时间
  private final int rpcTimeoutToNewActive;

  //hadoop配置
  private final Configuration conf;
  /*
   * Need a copy of conf for graceful fence to set
   * configurable retries for IPC client.
   * Refer HDFS-3561
   */
  private final Configuration gracefulFenceConf;

  //请求源
  private final RequestSource requestSource;
  
  public FailoverController(Configuration conf,
      RequestSource source) {
    this.conf = conf;
    this.gracefulFenceConf = new Configuration(conf);
    this.requestSource = source;
    
    this.gracefulFenceTimeout = getGracefulFenceTimeout(conf);
    this.rpcTimeoutToNewActive = getRpcTimeoutToNewActive(conf);

    //Configure less retries for graceful fence 优雅栏栅链接重试次数
    int gracefulFenceConnectRetries = conf.getInt(
        CommonConfigurationKeys.HA_FC_GRACEFUL_FENCE_CONNECTION_RETRIES,
        CommonConfigurationKeys.HA_FC_GRACEFUL_FENCE_CONNECTION_RETRIES_DEFAULT);
    gracefulFenceConf.setInt(
        CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY,
        gracefulFenceConnectRetries);
    gracefulFenceConf.setInt(
        CommonConfigurationKeys.IPC_CLIENT_CONNECT_MAX_RETRIES_ON_SOCKET_TIMEOUTS_KEY,
        gracefulFenceConnectRetries);
  }

  static int getGracefulFenceTimeout(Configuration conf) {
    return conf.getInt(
        CommonConfigurationKeys.HA_FC_GRACEFUL_FENCE_TIMEOUT_KEY,
        CommonConfigurationKeys.HA_FC_GRACEFUL_FENCE_TIMEOUT_DEFAULT);
  }
  
  static int getRpcTimeoutToNewActive(Configuration conf) {
    return conf.getInt(
        CommonConfigurationKeys.HA_FC_NEW_ACTIVE_TIMEOUT_KEY,
        CommonConfigurationKeys.HA_FC_NEW_ACTIVE_TIMEOUT_DEFAULT);
  }
  
  /**
   * 在给定的服务，我们计划故障转移，如进行预故障检查，以防止故障转移到服务（例如，由于它是无法访问的，已经激活，不是健康的，等等）。
   *
   * Perform pre-failover checks on the given service we plan to
   * failover to, eg to prevent failing over to a service (eg due
   * to it being inaccessible, already active, not healthy, etc).
   * 选择忽略toSvc如果它声称它是没有准备好成为案件提供了进行故障转移将使得它成为活跃的，
   * 例如，因为它触发一个日志回滚所以待机可以了解新的块和假安全模式活跃。
   * An option to ignore toSvc if it claims it is not ready to
   * become active is provided in case performing a failover will
   * allow it to become active, eg because it triggers a log roll
   * so the standby can learn about new blocks and leave safemode.
   *
   * @param from currently active service 当前活跃服务
   * @param target service to make active 使其变成新的活跃的服务
   * @param forceActive ignore toSvc if it reports that it is not ready 是否忽略toSrv如果它报告他还没准备好
   * @throws FailoverFailedException if we should avoid failover
   */
  private void preFailoverChecks(HAServiceTarget from,
                                 HAServiceTarget target,
                                 boolean forceActive)
      throws FailoverFailedException {
    HAServiceStatus toSvcStatus;
    HAServiceProtocol toSvc;

    if (from.getAddress().equals(target.getAddress())) {
      throw new FailoverFailedException(
          "Can't failover a service to itself");
    }

    try {
      toSvc = target.getProxy(conf, rpcTimeoutToNewActive);
      toSvcStatus = toSvc.getServiceStatus();
    } catch (IOException e) {
      String msg = "Unable to get service state for " + target;
      LOG.error(msg, e);
      throw new FailoverFailedException(msg, e);
    }

    if (!toSvcStatus.getState().equals(HAServiceState.STANDBY)) {
      throw new FailoverFailedException(
          "Can't failover to an " + toSvcStatus.getState() + " service");
    }
    
    if (!toSvcStatus.isReadyToBecomeActive()) {
      String notReadyReason = toSvcStatus.getNotReadyReason();
      if (!forceActive) {
        throw new FailoverFailedException(
            target + " is not ready to become active: " +
            notReadyReason);
      } else {
        LOG.warn("Service is not ready to become active, but forcing: {}",
            notReadyReason);
      }
    }

    try {
      HAServiceProtocolHelper.monitorHealth(toSvc, createReqInfo());
    } catch (HealthCheckFailedException hce) {
      throw new FailoverFailedException(
          "Can't failover to an unhealthy service", hce);
    } catch (IOException e) {
      throw new FailoverFailedException(
          "Got an IO exception", e);
    }
  }
  
  private StateChangeRequestInfo createReqInfo() {
    return new StateChangeRequestInfo(requestSource);
  }

  /**
   * Try to get the HA state of the node at the given address. This
   * function is guaranteed to be "quick" -- ie it has a short timeout
   * and no retries. Its only purpose is to avoid fencing a node that
   * has already restarted.
   */
  boolean tryGracefulFence(HAServiceTarget svc) {
    HAServiceProtocol proxy = null;
    try {
      proxy = svc.getProxy(gracefulFenceConf, gracefulFenceTimeout);
      proxy.transitionToStandby(createReqInfo());
      return true;
    } catch (ServiceFailedException sfe) {
      LOG.warn("Unable to gracefully make {} standby ({})",
          svc, sfe.getMessage());
    } catch (IOException ioe) {
      LOG.warn("Unable to gracefully make {} standby (unable to connect)",
          svc, ioe);
    } finally {
      if (proxy != null) {
        RPC.stopProxy(proxy);
      }
    }
    return false;
  }
  
  /**
   * Failover from service 1 to service 2. If the failover fails
   * then try to failback.
   *
   * @param fromSvc currently active service
   * @param toSvc service to make active
   * @param forceFence to fence fromSvc even if not strictly necessary
   * @param forceActive try to make toSvc active even if it is not ready
   * @throws FailoverFailedException if the failover fails
   */
  public void failover(HAServiceTarget fromSvc,
                       HAServiceTarget toSvc,
                       boolean forceFence,
                       boolean forceActive)
      throws FailoverFailedException {
    Preconditions.checkArgument(fromSvc.getFencer() != null,
        "failover requires a fencer");
    preFailoverChecks(fromSvc, toSvc, forceActive);

    // Try to make fromSvc standby
    boolean tryFence = true;
    
    if (tryGracefulFence(fromSvc)) {
      tryFence = forceFence;
    }

    // Fence fromSvc if it's required or forced by the user
    if (tryFence) {
      if (!fromSvc.getFencer().fence(fromSvc)) {
        throw new FailoverFailedException("Unable to fence " +
            fromSvc + ". Fencing failed.");
      }
    }

    // Try to make toSvc active
    boolean failed = false;
    Throwable cause = null;
    try {
      HAServiceProtocolHelper.transitionToActive(
          toSvc.getProxy(conf, rpcTimeoutToNewActive),
          createReqInfo());
    } catch (ServiceFailedException sfe) {
      LOG.error("Unable to make {} active ({}). Failing back.",
          toSvc, sfe.getMessage());
      failed = true;
      cause = sfe;
    } catch (IOException ioe) {
      LOG.error("Unable to make {} active (unable to connect). Failing back.",
          toSvc, ioe);
      failed = true;
      cause = ioe;
    }

    // We failed to make toSvc active
    if (failed) {
      String msg = "Unable to failover to " + toSvc;
      // Only try to failback if we didn't fence fromSvc
      if (!tryFence) {
        try {
          // Unconditionally fence toSvc in case it is still trying to
          // become active, eg we timed out waiting for its response.
          // Unconditionally force fromSvc to become active since it
          // was previously active when we initiated failover.
          failover(toSvc, fromSvc, true, true);
        } catch (FailoverFailedException ffe) {
          msg += ". Failback to " + fromSvc +
            " failed (" + ffe.getMessage() + ")";
          LOG.error(msg);
        }
      }
      throw new FailoverFailedException(msg, cause);
    }
  }
}
