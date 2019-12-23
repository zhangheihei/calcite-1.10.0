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
package org.apache.calcite.rel.metadata;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.runtime.FlatLists;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ImmutableNullableList;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.ReflectiveVisitor;
import org.apache.calcite.util.Util;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Implementation of the {@link RelMetadataProvider} interface that dispatches
 * metadata methods to methods on a given object via reflection.
 *
 * <p>The methods on the target object must be public and non-static, and have
 * the same signature as the implemented metadata method except for an
 * additional first parameter of type {@link RelNode} or a sub-class. That
 * parameter gives this provider an indication of that relational expressions it
 * can handle.</p>
 *
 * <p>For an example, see {@link RelMdColumnOrigins#SOURCE}.
 */
public class ReflectiveRelMetadataProvider
    implements RelMetadataProvider, ReflectiveVisitor {

  //~ Instance fields --------------------------------------------------------
  private final ConcurrentMap<Class<RelNode>, UnboundMetadata> map;
  private final Class<? extends Metadata> metadataClass0;
  private final ImmutableMultimap<Method, MetadataHandler> handlerMap;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a ReflectiveRelMetadataProvider.
   *
   * @param map Map
   * @param metadataClass0 Metadata class
   * @param handlerMap Methods handled and the objects to call them on
   */
  protected ReflectiveRelMetadataProvider(
      ConcurrentMap<Class<RelNode>, UnboundMetadata> map,
      Class<? extends Metadata> metadataClass0,
      Multimap<Method, MetadataHandler> handlerMap) {
    assert !map.isEmpty() : "are your methods named wrong?";
    //存放每个RelNode 对应的方法
    this.map = map;
    //存放metadata定义的的方法
    this.metadataClass0 = metadataClass0;
    //存放medata定义的方法和metadatahandler
    this.handlerMap = ImmutableMultimap.copyOf(handlerMap);
  }

  /** Returns an implementation of {@link RelMetadataProvider} that scans for
   * methods with a preceding argument.
   *
   * <p>For example, {@link BuiltInMetadata.Selectivity} has a method
   * {@link BuiltInMetadata.Selectivity#getSelectivity(RexNode)}.
   * A class</p>
   *
   * <blockquote><pre><code>
   * class RelMdSelectivity {
   *   public Double getSelectivity(Union rel, RexNode predicate) { }
   *   public Double getSelectivity(Filter rel, RexNode predicate) { }
   * </code></pre></blockquote>
   *
   * <p>provides implementations of selectivity for relational expressions
   * that extend {@link org.apache.calcite.rel.core.Union}
   * or {@link org.apache.calcite.rel.core.Filter}.</p>
   */
  public static RelMetadataProvider reflectiveSource(Method method,
      MetadataHandler target) {
    return reflectiveSource(target, ImmutableList.of(method));
  }

  /** Returns a reflective metadata provider that implements several
   * methods. */
  public static RelMetadataProvider reflectiveSource(MetadataHandler target,
      Method... methods) {
    //MetadataHandler中是具体的实现方法， methods是metadata定义的方法
    return reflectiveSource(target, ImmutableList.copyOf(methods));
  }

  //methods:metadata中的方法
  private static RelMetadataProvider
  reflectiveSource(final MetadataHandler target,
      final ImmutableList<Method> methods) {
    final Space2 space = Space2.create(target, methods);

    // This needs to be a concurrent map since RelMetadataProvider are cached in static
    // fields, thus the map is subject to concurrent modifications later.
    // See map.put in org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider.apply(
    // java.lang.Class<? extends org.apache.calcite.rel.RelNode>)
    final ConcurrentMap<Class<RelNode>, UnboundMetadata> methodsMap = new ConcurrentHashMap<>();
    //clasesed中存储的是:关系表达式:relnode, union等
    //Double getDistinctRowCount(RelNode rel, RelMetadataQuery mq,ImmutableBitSet groupKey,
    // RexNode predicate)
    //Double getDistinctRowCount(Union rel, RelMetadataQuery mq,ImmutableBitSet groupKey,
    // RexNode predicate)
    //Double getDistinctRowCount(Sort rel, RelMetadataQuery mq,ImmutableBitSet groupKey,
    // RexNode predicate) {
    //以每个RelNode为KEY，给每个KEY都绑定一个动态代理类(为啥这么做呢,为啥不能统一成一个动态代理呢)
    for (Class<RelNode> key : space.classes) {
      ImmutableNullableList.Builder<Method> builder =
          ImmutableNullableList.builder();
      //find就是 拿通过KEY(RelNode)找方法
      //直接找不到 就是拿实现接口
      for (final Method method : methods) {
        builder.add(space.find(key, method));
      }
      final List<Method> handlerMethods = builder.build();
      final UnboundMetadata function =
          new UnboundMetadata() {
            public Metadata bind(final RelNode rel,
                final RelMetadataQuery mq) {
              //是metadata的代理类
              return (Metadata) Proxy.newProxyInstance(
                  space.metadataClass0.getClassLoader(),
                  new Class[]{space.metadataClass0},
                  new InvocationHandler() {
                    public Object invoke(Object proxy, Method method,
                        Object[] args) throws Throwable {
                      // Suppose we are an implementation of Selectivity
                      // that wraps "filter", a LogicalFilter. Then we
                      // implement
                      //   Selectivity.selectivity(rex)
                      // by calling method
                      //   new SelectivityImpl().selectivity(filter, rex)
                      if (method.equals(
                          BuiltInMethod.METADATA_REL.method)) {
                        return rel;
                      }
                      if (method.equals(
                          BuiltInMethod.OBJECT_TO_STRING.method)) {
                        return space.metadataClass0.getSimpleName() + "(" + rel
                            + ")";
                      }
                      //注意methods/method是metadata的方法,不是medatahandler的
                      //public interface DistinctRowCount extends Metadata
                      //使用metadata中代码的位置，来寻找metadatahandler的代码
                      int i = methods.indexOf(method);
                      if (i < 0) {
                        throw new AssertionError("not handled: " + method
                            + " for " + rel);
                      }
                      //拿handlermethod
                      //因为已经为每个RelNOde绑定了handlerMethod
                      final Method handlerMethod = handlerMethods.get(i);
                      if (handlerMethod == null) {
                        throw new AssertionError("not handled: " + method
                            + " for " + rel);
                      }
                      final Object[] args1;
                      final List key;
                      if (args == null) {
                        args1 = new Object[]{rel, mq};
                        key = FlatLists.of(rel, method);
                      } else {
                        args1 = new Object[args.length + 2];
                        args1[0] = rel;
                        args1[1] = mq;
                        System.arraycopy(args, 0, args1, 2, args.length);

                        final Object[] args2 = args1.clone();
                        //为啥要替换RelMetadataQuery
                        //仅仅是为了做个KEY
                        args2[1] = method; // replace RelMetadataQuery with method
                        for (int j = 0; j < args2.length; j++) {
                          if (args2[j] == null) {
                            args2[j] = NullSentinel.INSTANCE;
                          } else if (args2[j] instanceof RexNode) {
                            // Can't use RexNode.equals - it is not deep
                            args2[j] = args2[j].toString();
                          }
                        }
                        key = FlatLists.copyOf(args2);
                      }
                      //args用来做KEY
                      //为啥要在RelMetadataQuery添加一个这个,填一个控制（占位符）
                      if (mq.map.put(key, NullSentinel.INSTANCE) != null) {
                        throw CyclicMetadataException.INSTANCE;
                      }
                      try {
                        return handlerMethod.invoke(target, args1);
                      } catch (InvocationTargetException
                          | UndeclaredThrowableException e) {
                        Throwables.propagateIfPossible(e.getCause());
                        throw e;
                      } finally {
                        mq.map.remove(key);
                      }
                    }
                  });
            }
          };
      methodsMap.put(key, function);
    }
    return new ReflectiveRelMetadataProvider(methodsMap, space.metadataClass0,
        space.providerMap);
  }

  public <M extends Metadata> Multimap<Method, MetadataHandler<M>>
  handlers(MetadataDef<M> def) {
    final ImmutableMultimap.Builder<Method, MetadataHandler<M>> builder =
        ImmutableMultimap.builder();
    for (Map.Entry<Method, MetadataHandler> entry : handlerMap.entries()) {
      if (def.methods.contains(entry.getKey())) {
        //noinspection unchecked
        builder.put(entry.getKey(), entry.getValue());
      }
    }
    return builder.build();
  }

  //判断是否为同名函数，方法权限必须为PUBLIC,并且不能为static
  private static boolean couldImplement(Method handlerMethod, Method method) {
    if (!handlerMethod.getName().equals(method.getName())
        || (handlerMethod.getModifiers() & Modifier.STATIC) != 0
        || (handlerMethod.getModifiers() & Modifier.PUBLIC) == 0) {
      return false;
    }

    //MetaDataHandler的方法参数
    final Class<?>[] parameterTypes1 = handlerMethod.getParameterTypes();
    //MetaData方法的参数
    final Class<?>[] parameterTypes = method.getParameterTypes();
    //    Double getDistinctRowCount(ImmutableBitSet groupKey, RexNode predicate);
    //    Double getDistinctRowCount(RelNode r, RelMetadataQuery mq,
    //    ImmutableBitSet groupKey, RexNode predicate);

    return parameterTypes1.length == parameterTypes.length + 2
        && RelNode.class.isAssignableFrom(parameterTypes1[0])
        && RelMetadataQuery.class == parameterTypes1[1]
        && Arrays.asList(parameterTypes)
            .equals(Util.skip(Arrays.asList(parameterTypes1), 2));
  }

  //~ Methods ----------------------------------------------------------------

  public <M extends Metadata> UnboundMetadata<M>
  apply(Class<? extends RelNode> relClass,
      Class<? extends M> metadataClass) {
    if (metadataClass == metadataClass0) {
      return apply(relClass);
    } else {
      return null;
    }
  }

  @SuppressWarnings({ "unchecked", "SuspiciousMethodCalls" })
  public <M extends Metadata> UnboundMetadata<M>
  apply(Class<? extends RelNode> relClass) {
    List<Class<? extends RelNode>> newSources = new ArrayList<>();
    for (;;) {
      UnboundMetadata<M> function = map.get(relClass);
      if (function != null) {
        for (@SuppressWarnings("rawtypes") Class clazz : newSources) {
          map.put(clazz, function);
        }
        return function;
      } else {
        newSources.add(relClass);
      }
      for (Class<?> interfaceClass : relClass.getInterfaces()) {
        if (RelNode.class.isAssignableFrom(interfaceClass)) {
          final UnboundMetadata<M> function2 = map.get(interfaceClass);
          if (function2 != null) {
            for (@SuppressWarnings("rawtypes") Class clazz : newSources) {
              map.put(clazz, function2);
            }
            return function2;
          }
        }
      }
      if (RelNode.class.isAssignableFrom(relClass.getSuperclass())) {
        relClass = (Class<RelNode>) relClass.getSuperclass();
      } else {
        return null;
      }
    }
  }

  /** Workspace for computing which methods can act as handlers for
   * given metadata methods. */
  static class Space {
    //记录对哪些RelNode实现了方法
    final Set<Class<RelNode>> classes = new HashSet<>();
    //RelNode:MetadataHandler中方法的第一个参数
    //Method：MetaData中的方法
    //Method:MetadataHandler中的方法
    final Map<Pair<Class<RelNode>, Method>, Method> handlerMap = new HashMap<>();
    final ImmutableMultimap<Method, MetadataHandler> providerMap;

    //Method: 为MetaData中定义的方法
    //MetadataHandler的实现类会去实现Metadata的同名函数，但会多参数
    Space(Multimap<Method, MetadataHandler> providerMap) {
      this.providerMap = ImmutableMultimap.copyOf(providerMap);

      // Find the distinct set of RelNode classes handled by this provider,
      // ordered base-class first.
      for (Map.Entry<Method, MetadataHandler> entry : providerMap.entries()) {
        final Method method = entry.getKey();
        final MetadataHandler provider = entry.getValue();
        //provider.getClass().getMethods():MetadataHandler的所有方法
        for (final Method handlerMethod : provider.getClass().getMethods()) {
          if (couldImplement(handlerMethod, method)) {
            //Double getDistinctRowCount(RelNode rel, RelMetadataQuery mq,
            // ImmutableBitSet groupKey, RexNode predicate)
            //Double getDistinctRowCount(Union rel, RelMetadataQuery mq,
            // ImmutableBitSet groupKey, RexNode predicate)
            //Double getDistinctRowCount(Sort rel, RelMetadataQuery mq,
            // ImmutableBitSet groupKey, RexNode predicate)

            @SuppressWarnings("unchecked") final Class<RelNode> relNodeClass =
                (Class<RelNode>) handlerMethod.getParameterTypes()[0];
            classes.add(relNodeClass);
            handlerMap.put(Pair.of(relNodeClass, method), handlerMethod);
          }
        }
      }
    }

    /** Finds an implementation of a method for {@code relNodeClass} or its
     * nearest base class. Assumes that base classes have already been added to
     * {@code map}. */
    @SuppressWarnings({ "unchecked", "SuspiciousMethodCalls" })
    //relNodeClass 假如是HiveProject
    Method find(final Class<? extends RelNode> relNodeClass, Method method) {
      Preconditions.checkNotNull(relNodeClass);
      for (Class r = relNodeClass;;) {
        Method implementingMethod = handlerMap.get(Pair.of(r, method));
        if (implementingMethod != null) {
          return implementingMethod;
        }
        //HiveProject 实现RelNode,最终就会拿到RelNode对应的实现方法
        for (Class<?> clazz : r.getInterfaces()) {
          if (RelNode.class.isAssignableFrom(clazz)) {
            implementingMethod = handlerMap.get(Pair.of(clazz, method));
            if (implementingMethod != null) {
              return implementingMethod;
            }
          }
        }
        r = r.getSuperclass();
        if (r == null || !RelNode.class.isAssignableFrom(r)) {
          throw new IllegalArgumentException("No handler for method [" + method
              + "] applied to argument of type [" + relNodeClass
              + "]; we recommend you create a catch-all (RelNode) handler");
        }
      }
    }
  }

  /** Extended work space. */
  static class Space2 extends Space {
    private Class<Metadata> metadataClass0;

    public Space2(Class<Metadata> metadataClass0,
        ImmutableMultimap<Method, MetadataHandler> providerMap) {
      super(providerMap);
      //记录该metadata类型 比如DistinctRowCount，Collation等
      this.metadataClass0 = metadataClass0;
    }

    public static Space2 create(MetadataHandler target,
        ImmutableList<Method> methods) {
      assert methods.size() > 0;
      final Method method0 = methods.get(0);
      //noinspection unchecked
      Class<Metadata> metadataClass0 = (Class) method0.getDeclaringClass();
      assert Metadata.class.isAssignableFrom(metadataClass0);

      //method必须是同一个类，metadata
      for (Method method : methods) {
        assert method.getDeclaringClass() == metadataClass0;
      }

      final ImmutableMultimap.Builder<Method, MetadataHandler> providerBuilder =
          ImmutableMultimap.builder();
      for (final Method method : methods) {
        providerBuilder.put(method, target);
      }

      //把MetaData实现类的 反射方法的所有类给记录到这里， MetadataHandler是的实现类 会实现同名反射方法的同名函数
      return new Space2(metadataClass0, providerBuilder.build());
    }
  }
}

// End ReflectiveRelMetadataProvider.java
