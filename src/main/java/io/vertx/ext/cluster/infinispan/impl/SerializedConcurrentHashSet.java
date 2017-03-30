package io.vertx.ext.cluster.infinispan.impl;

import java.io.Serializable;

import io.vertx.core.impl.ConcurrentHashSet;

public class SerializedConcurrentHashSet<V> extends ConcurrentHashSet implements Serializable {
}
