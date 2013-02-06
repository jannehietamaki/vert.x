/*
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.vertx.java.core.impl;

import org.vertx.java.core.Handler;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.core.logging.impl.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public abstract class Context {

  private static final Logger log = LoggerFactory.getLogger(Context.class);

  private final VertxInternal vertx;
  private DeploymentHandle deploymentContext;
  private Path pathAdjustment;
  private Map<Object, Runnable> closeHooks;
  private final Executor bgExec;
  private final ClassLoader tccl;
  private AtomicInteger outstandingTasks = new AtomicInteger();
  private Handler<Void> closedHandler;
  private boolean closed;

  protected Context(VertxInternal vertx, Executor bgExec) {
    this.vertx = vertx;
  	this.bgExec = bgExec;
    this.tccl = Thread.currentThread().getContextClassLoader();
  }

  public void setTCCL() {
    Thread.currentThread().setContextClassLoader(tccl);
  }

  public void setDeploymentHandle(DeploymentHandle deploymentHandle) {
    this.deploymentContext = deploymentHandle;
  }

  public DeploymentHandle getDeploymentHandle() {
    return deploymentContext;
  }

  public Path getPathAdjustment() {
    return pathAdjustment;
  }

  public void setPathAdjustment(Path pathAdjustment) {
    this.pathAdjustment = pathAdjustment;
  }

  public void reportException(Throwable t) {
    if (deploymentContext != null) {
      deploymentContext.reportException(t);
    } else {
      t.printStackTrace();
      log.error("context Unhandled exception", t);
    }
  }

  public Runnable getCloseHook(Object key) {
    return closeHooks == null ? null : closeHooks.get(key);
  }

  public void putCloseHook(Object key, Runnable hook) {
    if (closeHooks == null) {
      closeHooks = new HashMap<>();
    }
    closeHooks.put(key, hook);
  }

  public void runCloseHooks() {
    if (closeHooks != null) {
      for (Runnable hook: closeHooks.values()) {
        try {
          hook.run();
        } catch (Throwable t) {
          reportException(t);
        }
      }
    }
  }

  private void close() {
    vertx.setContext(null);
    Thread.currentThread().setContextClassLoader(null);
    closed = true;
  }

  public abstract void execute(Runnable handler);

  protected void executeOnWorker(final Runnable task) {
    final Runnable wrapped = wrapTask(task);
    if (wrapped != null) {
      bgExec.execute(new Runnable() {
        public void run() {
          wrapped.run();
        }
      });
    }
  }

  private void decOustanding() {
    if (outstandingTasks.decrementAndGet() == 0) {
      closedHandler.handle(null);
      // Now there are no more oustanding tasks we can close the context
      close();
    }
  }

  public void closedHandler(Handler<Void> closedHandler) {
    this.closedHandler = closedHandler;
    // Start with 1 - this represents the stop task itself
    this.outstandingTasks.set(1);
  }

  private boolean isClosedHandlerSet() {
    return closedHandler != null;
  }

  protected Runnable wrapTask(final Runnable task) {
    if (closed) {
      return null;
    }
    // TODO this is ugly - refactor
    final boolean hasClosedHandler = isClosedHandlerSet();
    if (hasClosedHandler) {
      outstandingTasks.incrementAndGet();
    }
    return new Runnable() {
      public void run() {
        boolean closedHandlerSet = isClosedHandlerSet();
        try {
          vertx.setContext(Context.this);
          task.run();
        } catch (Throwable t) {
          reportException(t);
        }
        boolean closedHandlerNowSet = isClosedHandlerSet();
        // The closed handler could have been set in the task itself, we check this and if so
        // we decrement
        if (hasClosedHandler || (!closedHandlerSet && closedHandlerNowSet)) {
          decOustanding();
        }
      }
    };
  }
}
