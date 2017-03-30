/*
 * Copyright 2016 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.ext.cluster.infinispan.impl;

import java.io.Serializable;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Predicate;

import org.infinispan.Cache;
import org.infinispan.util.function.SerializableBiFunction;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ConcurrentHashSet;
import io.vertx.core.impl.TaskQueue;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.spi.cluster.AsyncMultiMap;
import io.vertx.core.spi.cluster.ChoosableIterable;

/**
 * @author Thomas Segismont
 */
public class InfinispanAsyncMultiMap<K, V> implements AsyncMultiMap<K, V>, Serializable {

  private final VertxInternal vertx;
  private final Cache<Object, Set<Object>> cache;
  private final TaskQueue taskQueue;

  public InfinispanAsyncMultiMap(Vertx vertx, Cache<Object, Set<Object>> cache) {
    this.vertx = (VertxInternal) vertx;
    this.cache = cache;
    taskQueue = new TaskQueue();
  }

  @Override
  public void add(K k, V v, Handler<AsyncResult<Void>> completionHandler) {
    Object kk = DataConverter.toCachedObject(k);
    Object vv = DataConverter.toCachedObject(v);
    vertx.getOrCreateContext().executeBlocking(fut -> {
      SerializableBiFunction<Object, Set<Object>, Set<Object>> computeFunction = (key, values) -> {
        if (values == null)
          values = new SerializedConcurrentHashSet<>();
        values.add(vv);
        return values;
      };
      cache.compute(kk, computeFunction);
      fut.complete();
    }, taskQueue, completionHandler);
  }

  @Override
  public void get(K k, Handler<AsyncResult<ChoosableIterable<V>>> resultHandler) {
    vertx.getOrCreateContext().<ChoosableIterable<V>>executeBlocking(fut -> {
      Set<Object> entries = cache.get(DataConverter.toCachedObject(k));
      ChoosableSet<V> sids;
      if (entries == null) {
        sids = new ChoosableSet<>(0);
      } else {
        sids = new ChoosableSet<>(entries.size());
        for (Object entry : entries) {
          sids.add(DataConverter.fromCachedObject(entry));
        }
      }
      sids.setInitialised();
      fut.complete(sids);
    }, taskQueue, res -> {
      resultHandler.handle(res);
    });
  }

  @Override
  public void remove(K k, V v, Handler<AsyncResult<Boolean>> completionHandler) {
    Object kk = DataConverter.toCachedObject(k);
    Object vv = DataConverter.toCachedObject(v);
    vertx.getOrCreateContext().executeBlocking(fut -> {
      SerializableBiFunction<Object, Set<Object>, Set<Object>> computeFunction = (key, values) -> {
        if (values != null)
          values.remove(vv);
        return values == null || values.isEmpty() ? null : values;
      };
      Set<Object> result = cache.compute(kk, computeFunction);
      fut.complete(result == null);
    }, taskQueue, completionHandler);
  }

  @Override
  public void removeAllForValue(V v, Handler<AsyncResult<Void>> completionHandler) {
    vertx.getOrCreateContext().executeBlocking(future -> {
      Object vv = DataConverter.toCachedObject(v);
      cache.keySet().stream().forEach((cache, key) ->
        cache.computeIfPresent(key, (o, o1) -> {
          Set values = (Set) o1;
          values.remove(vv);
          return values == null || values.isEmpty() ? null : values;
        }));
      future.complete();
    }, taskQueue, completionHandler);
  }

  @Override
  public void removeAllMatching(Predicate<V> p, Handler<AsyncResult<Void>> completionHandler) {
    vertx.getOrCreateContext().executeBlocking(future -> {
      cache.keySet().stream().forEach((cache, key) ->
        cache.computeIfPresent(DataConverter.fromCachedObject(key), (o, o1) -> {
            Set values = (Set) o1;
            values.removeIf(val -> p.test(DataConverter.fromCachedObject(val)));
            return values == null || values.isEmpty() ? null : values;
          })
      );
      future.complete();
    }, taskQueue, completionHandler);
  }

  public void clearCache() {
    cache.clear();
  }

  /**
   * @author <a href="http://tfox.org">Tim Fox</a>
   */
  private static class ChoosableSet<T> implements ChoosableIterable<T> {

    private volatile boolean initialised;
    private final Set<T> ids;
    private volatile Iterator<T> iter;

    public ChoosableSet(int initialSize) {
      ids = new ConcurrentHashSet<>(initialSize);
    }

    public boolean isInitialised() {
      return initialised;
    }

    public void setInitialised() {
      this.initialised = true;
    }

    public void add(T elem) {
      ids.add(elem);
    }

    public void remove(T elem) {
      ids.remove(elem);
    }

    public void merge(ChoosableSet<T> toMerge) {
      ids.addAll(toMerge.ids);
    }

    public boolean isEmpty() {
      return ids.isEmpty();
    }

    @Override
    public Iterator<T> iterator() {
      return ids.iterator();
    }

    public synchronized T choose() {
      if (!ids.isEmpty()) {
        if (iter == null || !iter.hasNext()) {
          iter = ids.iterator();
        }
        try {
          return iter.next();
        } catch (NoSuchElementException e) {
          return null;
        }
      } else {
        return null;
      }
    }
  }
}
