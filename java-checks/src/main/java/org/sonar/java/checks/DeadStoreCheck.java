/*
 * SonarQube Java
 * Copyright (C) 2012 SonarSource
 * sonarqube@googlegroups.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.cfg.CFG;
import org.sonar.java.cfg.LiveVariables;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;
import org.sonar.squidbridge.annotations.ActivatedByDefault;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Rule(
    key = "S1854",
    name = "Dead stores should be removed",
    tags = {"cert", "cwe", "suspicious", "unused"},
    priority = Priority.MAJOR)
@ActivatedByDefault
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.DATA_RELIABILITY)
@SqaleConstantRemediation("15min")
public class DeadStoreCheck extends SubscriptionBaseVisitor {

  @Override
  public List<Tree.Kind> nodesToVisit() {
    return ImmutableList.of(Tree.Kind.METHOD);
  }

  @Override
  public void visitNode(Tree tree) {
    MethodTree methodTree = (MethodTree) tree;
    if(methodTree.block() == null) {
      return;
    }
    CFG cfg = CFG.build(methodTree);
    LiveVariables liveVariables = LiveVariables.analyze(cfg);
    for (CFG.Block block : cfg.blocks()) {
      Set<Symbol> out = new HashSet<>(liveVariables.getOut(block));
      Set<Tree> assignmentLHS = new HashSet<>();
      for (Tree element : Lists.reverse(block.elements())) {
        Symbol symbol;
        switch (element.kind()) {
          case ASSIGNMENT:
            ExpressionTree lhs = ((AssignmentExpressionTree) element).variable();
            if (lhs.is(Tree.Kind.IDENTIFIER)) {
              symbol = ((IdentifierTree) lhs).symbol();
              if (isLocalVariable(symbol) && !out.contains(symbol)) {
                addIssue(element, "Remove this useless assignment to local variable \"" + symbol.name() + "\".");
              }
              assignmentLHS.add(lhs);
              out.remove(symbol);
            }
            break;
          case IDENTIFIER:
            if (!assignmentLHS.contains(element)) {
              symbol = ((IdentifierTree) element).symbol();
              if (isLocalVariable(symbol)) {
                out.add(symbol);
              }
            }
            break;
          case VARIABLE:
            VariableTree localVar = (VariableTree) element;
            symbol = localVar.symbol();
            if(localVar.initializer() != null && !out.contains(symbol)) {
              addIssue(element, "Remove this useless assignment to local variable \"" + symbol.name() + "\".");
            }
            out.remove(symbol);
        }
      }

    }

  }

  private static boolean isLocalVariable(Symbol symbol) {
    return symbol.owner().isMethodSymbol();
  }
}
