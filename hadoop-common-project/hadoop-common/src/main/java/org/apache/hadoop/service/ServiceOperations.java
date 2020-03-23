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

package org.apache.hadoop.service;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *此类包含一组与服务一起使用的方法，尤其是可以遍历它们的生命周期。
 * This class contains a set of methods to work with services, especially
 * to walk them through their lifecycle.
 */
@Public
@Evolving
public final class ServiceOperations {
  private static final Logger LOG =
      LoggerFactory.getLogger(AbstractService.class);

  private ServiceOperations() {
  }

  /**
   * Stop a service.
   * <p>Do nothing if the service is null or not
   * in a state in which it can be/needs to be stopped.
   * <p>
   * The service state is checked <i>before</i> the operation begins.
   * This process is <i>not</i> thread safe.
   * @param service a service or null
   */
  public static void stop(Service service) {
    if (service != null) {
      service.stop();
    }
  }

  /**
   * 停止一个服务，如果他是null什么都不做，异常捕获和日志在warn几把，但是不抛出。这个操作用于使用在cleanup时期
   * Stop a service; if it is null do nothing. Exceptions are caught and
   * logged at warn level. (but not Throwables). This operation is intended to
   * be used in cleanup operations
   *
   * @param service a service; may be null
   * @return any exception that was caught; null if none was.
   */
  public static Exception stopQuietly(Service service) {
    return stopQuietly(LOG, service);
  }

  /**
   * Stop a service; if it is null do nothing. Exceptions are caught and
   * logged at warn level. (but not Throwables). This operation is intended to
   * be used in cleanup operations
   *
   * @param log the log to warn at
   * @param service a service; may be null
   * @return any exception that was caught; null if none was.
   * @see ServiceOperations#stopQuietly(Service)
   */
  public static Exception stopQuietly(Log log, Service service) {
    try {
      stop(service);
    } catch (Exception e) {
      log.warn("When stopping the service " + service.getName(), e);
      return e;
    }
    return null;
  }

  /**
   * Stop a service; if it is null do nothing. Exceptions are caught and
   * logged at warn level. (but not Throwables). This operation is intended to
   * be used in cleanup operations
   *
   * @param log the log to warn at
   * @param service a service; may be null
   * @return any exception that was caught; null if none was.
   * @see ServiceOperations#stopQuietly(Service)
   */
  public static Exception stopQuietly(Logger log, Service service) {
    try {
      stop(service);
    } catch (Exception e) {
      log.warn("When stopping the service {}", service.getName(), e);
      return e;
    }
    return null;
  }

  /**
   * 用于管理{@link ServiceStateChangeListener}实例列表的类，
   * Class to manage a list of {@link ServiceStateChangeListener} instances,
   * 包括一个通知循环，该通知循环可在通知过程中抵御列表的更改。
   * including a notification loop that is robust against changes to the list
   * during the notification process.
   */
  public static class ServiceListeners {
    /**
     * List of state change listeners; it is final to guarantee
     * that it will never be null.
     * 状态改变监听器的列表，它是最终保证它不为null
     */
    private final List<ServiceStateChangeListener> listeners =
      new ArrayList<ServiceStateChangeListener>();

    /**
     * Thread-safe addition of a new listener to the end of a list.
     * Attempts to re-register a listener that is already registered
     * will be ignored.
     * @param l listener
     */
    public synchronized void add(ServiceStateChangeListener l) {
      if(!listeners.contains(l)) {
        listeners.add(l);
      }
    }

    /**
     * Remove any registration of a listener from the listener list.
     * @param l listener
     * @return true if the listener was found (and then removed)
     */
    public synchronized boolean remove(ServiceStateChangeListener l) {
      return listeners.remove(l);
    }

    /**
     * Reset the listener list
     */
    public synchronized void reset() {
      listeners.clear();
    }

    /**
     * 更改为新状态并通知所有侦听器。
     * Change to a new state and notify all listeners.
     * 该方法将阻塞，直到发出所有通知为止。
     * This method will block until all notifications have been issued.
     * 在通知开始之前，它会缓存监听器列表，
     * It caches the list of listeners before the notification begins,
     * 因此添加或删除监听器将不可见。
     * so additions or removal of listeners will not be visible.
     * @param service the service that has changed state
     */
    public void notifyListeners(Service service) {
      //对回调列表进行快速快照
      //take a very fast snapshot of the callback list
      //very much like CopyOnWriteArrayList, only more minimal
      ServiceStateChangeListener[] callbacks;
      //将监听器转换为回调数组快照
      synchronized (this) {
        callbacks = listeners.toArray(new ServiceStateChangeListener[listeners.size()]);
      }
      //iterate through the listeners outside the synchronized method,
      //ensuring that listener registration/unregistration doesn't break anything
      for (ServiceStateChangeListener l : callbacks) {
        //改变状态
        l.stateChanged(service);
      }
    }
  }

}
