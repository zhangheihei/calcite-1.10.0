/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.util.graph;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Iterates over the vertices in a directed graph in depth-first order.
 *
 * @param <V> Vertex type
 * @param <E> Edge type
 */
public class DepthFirstIterator<V, E extends DefaultEdge>
    implements Iterator<V> {
  private final Iterator<V> iterator;

  public DepthFirstIterator(DirectedGraph<V, E> graph, V start) {
    // Dumb implementation that builds the list first.
    iterator = buildList(graph, start).iterator();
  }

  private static <V, E extends DefaultEdge> List<V> buildList(
      DirectedGraph<V, E> graph, V start) {
    final List<V> list = Lists.newArrayList();
    //创建一个空set, V是HepRelVertex,E是DefaulterEdge
    //这个SET在这里的作用没明白，什么叫ACTIVE
    buildListRecurse(list, Sets.<V>newHashSet(), graph, start);
    return list;
  }

  /** Creates an iterable over the vertices in the given graph in a depth-first
   * iteration order. */
  //深度遍历，把图中所有的顶点遍历出来
  public static <V, E extends DefaultEdge> Iterable<V> of(
      DirectedGraph<V, E> graph, V start) {
    // Doesn't actually return a DepthFirstIterator, but a list with the same
    // contents, which is more efficient.
    return buildList(graph, start);
  }

  /** Populates a collection with the nodes reachable from a given node. */
  public static <V, E extends DefaultEdge> void reachable(Collection<V> list,
      final DirectedGraph<V, E> graph, final V start) {
    buildListRecurse(list, new HashSet<V>(), graph, start);
  }

  //start是起始顶点 ,递归的建立list
  //HiveProject->HiveJoin->HiveProject->HiveFilter->HiveTableScan
  //                     ->HiveProject->HiveAggregate->HiveProject->HiveFilter->HiveTableScan
  private static <V, E extends DefaultEdge> void buildListRecurse(
      Collection<V> list, Set<V> activeVertices, DirectedGraph<V, E> graph,
      V start) {
    //为啥会报错
    //入口添加
    //当发现重复的节点进去是，防止无限递归，无法跳出逻辑
    if (!activeVertices.add(start)) {
      return;
    }
    //往list中 增加HepRelVertex节点
    list.add(start);
    //拿到顶点对应的边
    List<E> edges = graph.getOutwardEdges(start);
    //对每个顶点上的边都
    for (E edge : edges) {
      //noinspection unchecked
      buildListRecurse(list, activeVertices, graph, (V) edge.target);
    }
    //出口 删除
    //最终activeVertices是空的
    activeVertices.remove(start);
  }

  public boolean hasNext() {
    return iterator.hasNext();
  }

  public V next() {
    return iterator.next();
  }

  public void remove() {
    throw new UnsupportedOperationException();
  }
}

// End DepthFirstIterator.java
