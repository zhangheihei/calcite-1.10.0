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
package org.apache.calcite.rel.rules;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;

/**
 * Planner rule that pushes
 * a {@link org.apache.calcite.rel.logical.LogicalFilter}
 * past a {@link org.apache.calcite.rel.logical.LogicalProject}.
 */
//转换Filter和Project的顺序，先做Filter 缩小关系，再project
public class FilterProjectTransposeRule extends RelOptRule {
  /** The default instance of
   * {@link org.apache.calcite.rel.rules.FilterProjectTransposeRule}.
   *
   * <p>It matches any kind of join or filter, and generates the same kind of
   * join and filter. */
  public static final FilterProjectTransposeRule INSTANCE =
      new FilterProjectTransposeRule(Filter.class, Project.class, true, true,
          RelFactories.LOGICAL_BUILDER);

  private final boolean copyFilter;
  private final boolean copyProject;

  //~ Constructors -----------------------------------------------------------

  /**
   * Creates a FilterProjectTransposeRule.
   *
   * <p>If {@code filterFactory} is null, creates the same kind of filter as
   * matched in the rule. Similarly {@code projectFactory}.</p>
   */
  public FilterProjectTransposeRule(
      Class<? extends Filter> filterClass,
      Class<? extends Project> projectClass,
      boolean copyFilter, boolean copyProject,
      RelBuilderFactory relBuilderFactory) {
    super(
        operand(filterClass,
            operand(projectClass, any())),
        relBuilderFactory, null);
    this.copyFilter = copyFilter;
    this.copyProject = copyProject;
  }

  @Deprecated // to be removed before 2.0
  public FilterProjectTransposeRule(
      Class<? extends Filter> filterClass,
      RelFactories.FilterFactory filterFactory,
      Class<? extends Project> projectClass,
      RelFactories.ProjectFactory projectFactory) {
    this(filterClass, projectClass, filterFactory == null,
        projectFactory == null,
        RelBuilder.proto(filterFactory, projectFactory));
  }

  //~ Methods ----------------------------------------------------------------

  public void onMatch(RelOptRuleCall call) {
    final Filter filter = call.rel(0);
    final Project project = call.rel(1);

    if (RexOver.containsOver(project.getProjects(), null)) {
      // In general a filter cannot be pushed below a windowing calculation.
      // Applying the filter before the aggregation function changes
      // the results of the windowing invocation.
      //
      // When the filter is on the PARTITION BY expression of the OVER clause
      // it can be pushed down. For now we don't support this.
      return;
    }

    if (RexUtil.containsCorrelation(filter.getCondition())) {
      // If there is a correlation condition anywhere in the filter, don't
      // push this filter past project since in some cases it can prevent a
      // Correlate from being de-correlated.
      return;
    }

    // convert the filter to one that references the child of the project
    RexNode newCondition =
        RelOptUtil.pushPastProject(filter.getCondition(), project);

    // Remove cast of BOOLEAN NOT NULL to BOOLEAN or vice versa. Filter accepts
    // nullable and not-nullable conditions, but a CAST might get in the way of
    // other rewrites.
    final RelDataTypeFactory typeFactory = filter.getCluster().getTypeFactory();
    if (RexUtil.isNullabilityCast(typeFactory, newCondition)) {
      newCondition = ((RexCall) newCondition).getOperands().get(0);
    }

    final RelBuilder relBuilder = call.builder();
    RelNode newFilterRel =
        copyFilter
            ? filter.copy(filter.getTraitSet(), project.getInput(),
                newCondition)
            : relBuilder.push(project.getInput()).filter(newCondition).build();

    RelNode newProjRel =
        copyProject
            ? project.copy(project.getTraitSet(), newFilterRel,
                project.getProjects(), project.getRowType())
            : relBuilder.push(newFilterRel)
                .project(project.getProjects(), project.getRowType().getFieldNames())
                .build();

    call.transformTo(newProjRel);
  }
}

// End FilterProjectTransposeRule.java
