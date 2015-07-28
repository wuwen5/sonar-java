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
package org.sonar.java.cfg;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.BinaryExpressionTree;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.CaseGroupTree;
import org.sonar.plugins.java.api.tree.ConditionalExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionStatementTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.IfStatementTree;
import org.sonar.plugins.java.api.tree.LiteralTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.ParenthesizedTree;
import org.sonar.plugins.java.api.tree.ReturnStatementTree;
import org.sonar.plugins.java.api.tree.StatementTree;
import org.sonar.plugins.java.api.tree.SwitchStatementTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

import javax.annotation.Nullable;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class CFG {

  private final Block exitBlock;
  private Block currentBlock;

  /**
   * List of all blocks in order they were created.
   */
  final List<Block> blocks = new ArrayList<>();

  private final Deque<Block> breakTargets = new LinkedList<>();

  private final Deque<Block> switches = new LinkedList<>();

  public Block entry() {
    return currentBlock;
  }

  public static class Block {
    public final int id;
    final List<Tree> elements = new ArrayList<>();
    public List<Block> successors = Lists.newArrayList();
    public Tree terminator;

    public Block(int id) {
      this.id = id;
    }

    public List<Tree> elements() {
      return Lists.reverse(elements);
    }
  }

  private CFG(BlockTree tree) {
    exitBlock = createBlock();
    currentBlock = createBlock(exitBlock);
    for (StatementTree statementTree : Lists.reverse(tree.body())) {
      build(statementTree);
    }
  }

  private Block createBlock(Block successor) {
    Block result = createBlock();
    result.successors.add(successor);
    return result;
  }

  private Block createBlock() {
    Block result = new Block(blocks.size());
    blocks.add(result);
    return result;
  }

  public static CFG build(MethodTree tree) {
    Preconditions.checkArgument(tree.block() != null, "Cannot build CFG for method with no body.");
    return new CFG(tree.block());
  }

  private void build(List<? extends Tree> trees) {
    for (Tree tree : Lists.reverse(trees)) {
      build(tree);
    }
  }

  private void build(Tree tree) {
    switch (tree.kind()) {
      case BLOCK:
        for (StatementTree statementTree : Lists.reverse(((BlockTree) tree).body())) {
          build(statementTree);
        }
        break;
      case RETURN_STATEMENT: {
        ReturnStatementTree s = (ReturnStatementTree) tree;
        currentBlock = createUnconditionalJump(s, exitBlock);
        ExpressionTree expression = s.expression();
        if (expression != null) {
          build(expression);
        }
        break;
      }
      case EXPRESSION_STATEMENT:
        build(((ExpressionStatementTree) tree).expression());
        break;
      case METHOD_INVOCATION:
        MethodInvocationTree mit = (MethodInvocationTree) tree;
        currentBlock.elements.add(mit);
        build(mit.methodSelect());
        for (ExpressionTree arg : Lists.reverse(mit.arguments())) {
          build(arg);
        }
        break;
      case IF_STATEMENT: {
        IfStatementTree ifStatementTree = (IfStatementTree) tree;
        Block next = currentBlock;
        // process else-branch
        Block elseBlock = next;
        StatementTree elseStatement = ifStatementTree.elseStatement();
        if (elseStatement != null) {
          // if statement will create the required block.
          if (!elseStatement.is(Tree.Kind.IF_STATEMENT)) {
            currentBlock = createBlock(next);
          }
          build(elseStatement);
          elseBlock = currentBlock;
        }
        // process then-branch
        currentBlock = createBlock(next);
        build(ifStatementTree.thenStatement());
        Block thenBlock = currentBlock;
        // process condition
        currentBlock = createBranch(ifStatementTree, thenBlock, elseBlock);
        buildCondition(ifStatementTree.condition(), thenBlock, elseBlock);
        break;
      }
      case CONDITIONAL_EXPRESSION: {
        ConditionalExpressionTree cond = (ConditionalExpressionTree) tree;
        Block next = currentBlock;
        // process else-branch
        ExpressionTree elseStatement = cond.falseExpression();
        currentBlock = createBlock(next);
        build(elseStatement);
        Block elseBlock = currentBlock;
        // process then-branch
        currentBlock = createBlock(next);
        build(cond.trueExpression());
        Block thenBlock = currentBlock;
        // process condition
        currentBlock = createBranch(cond, thenBlock, elseBlock);
        buildCondition(cond.condition(), thenBlock, elseBlock);
        break;
      }
      case VARIABLE:
        currentBlock.elements.add(tree);
        VariableTree variableTree = (VariableTree) tree;
        if (variableTree.initializer() != null) {
          build(variableTree.initializer());
        }
        break;
      case EQUAL_TO:
      case NOT_EQUAL_TO:
        BinaryExpressionTree binaryExpressionTree = (BinaryExpressionTree) tree;
        currentBlock.elements.add(tree);
        build(binaryExpressionTree.rightOperand());
        build(binaryExpressionTree.leftOperand());
        break;
      case ASSIGNMENT:
        AssignmentExpressionTree assignmentExpressionTree = (AssignmentExpressionTree) tree;
        currentBlock.elements.add(tree);
        build(assignmentExpressionTree.expression());
        break;
      case MEMBER_SELECT:
        MemberSelectExpressionTree mse = (MemberSelectExpressionTree) tree;
        currentBlock.elements.add(mse);
        build(mse.expression());
        break;
      case IDENTIFIER:
        currentBlock.elements.add(tree);
        break;
      case CONDITIONAL_AND: {
        BinaryExpressionTree e = (BinaryExpressionTree) tree;
        // process RHS
        Block falseBlock = currentBlock;
        currentBlock = createBlock(falseBlock);
        build(e.rightOperand());
        Block trueBlock = currentBlock;
        // process LHS
        currentBlock = createBranch(e, trueBlock, falseBlock);
        build(e.leftOperand());
        break;
      }
      case CONDITIONAL_OR: {
        BinaryExpressionTree e = (BinaryExpressionTree) tree;
        // process RHS
        Block trueBlock = currentBlock;
        currentBlock = createBlock(trueBlock);
        build(e.rightOperand());
        Block falseBlock = currentBlock;
        // process LHS
        currentBlock = createBranch(e, trueBlock, falseBlock);
        build(e.leftOperand());
        break;
      }
      case SWITCH_STATEMENT: {
        // FIXME useless node created for default cases.
        SwitchStatementTree switchStatementTree = (SwitchStatementTree) tree;
        Block switchSuccessor = currentBlock;
        // process condition
        currentBlock = createBlock();
        currentBlock.terminator = switchStatementTree;
        switches.addLast(currentBlock);
        build(switchStatementTree.expression());
        // process body
        currentBlock = createBlock(switchSuccessor);
        breakTargets.addLast(switchSuccessor);
        if (!switchStatementTree.cases().isEmpty()) {
          CaseGroupTree firstCase = switchStatementTree.cases().get(0);
          for (CaseGroupTree caseGroupTree : switchStatementTree.cases()) {
            build(caseGroupTree.body());
            switches.getLast().successors.add(currentBlock);
            if (caseGroupTree != firstCase) {
              // No block predecessing the first case group.
              currentBlock = createBlock(currentBlock);
            }
          }
        }
        breakTargets.removeLast();
        // process condition
        currentBlock = switches.removeLast();
        break;
      }
      case BREAK_STATEMENT: {
        if (breakTargets.isEmpty()) {
          throw new IllegalStateException("'break' statement not in loop or switch statement");
        }
        currentBlock = createUnconditionalJump(tree, breakTargets.getLast());
        break;
      }
    }

  }

  private Block createUnconditionalJump(Tree terminator, @Nullable Block target) {
    Block result = createBlock();
    result.terminator = terminator;
    if (target != null) {
      result.successors.add(target);
    }
    return result;
  }

  private void buildCondition(Tree syntaxNode, Block trueBlock, Block falseBlock) {
    switch (syntaxNode.kind()) {
      case CONDITIONAL_OR: {
        BinaryExpressionTree e = (BinaryExpressionTree) syntaxNode;
        // process RHS
        buildCondition(e.rightOperand(), trueBlock, falseBlock);
        falseBlock = currentBlock;
        // process LHS
        currentBlock = createBranch(e, trueBlock, falseBlock);
        buildCondition(e.leftOperand(), trueBlock, falseBlock);
        break;
      }
      case CONDITIONAL_AND: {
        // process RHS
        BinaryExpressionTree e = (BinaryExpressionTree) syntaxNode;
        buildCondition(e.rightOperand(), trueBlock, falseBlock);
        trueBlock = currentBlock;
        // process LHS
        currentBlock = createBranch(e, trueBlock, falseBlock);
        buildCondition(e.leftOperand(), trueBlock, falseBlock);
        break;
      }
      // Skip syntactic sugar:
      case PARENTHESIZED_EXPRESSION:
        buildCondition(((ParenthesizedTree) syntaxNode).expression(), trueBlock, falseBlock);
        break;
      default:
        build(syntaxNode);
        break;
    }
  }

  private Block createBranch(Tree terminator, Block trueBranch, Block falseBranch) {
    Block result = createBlock();
    result.terminator = terminator;
    result.successors.add(trueBranch);
    result.successors.add(falseBranch);
    return result;
  }

  public void debugTo(PrintStream out) {
    for (Block block : Lists.reverse(blocks)) {
      if (block.id != 0) {
        out.println("B" + block.id + ":");
      } else {
        out.println("B" + block.id + " (Exit) :");
      }
      int i = 0;
      for (Tree tree : block.elements()) {
        out.println("  " + i + ": " + syntaxNodeToDebugString(tree));
        i++;
      }
      if (block.terminator != null) {
        out.println("  T: " + syntaxNodeToDebugString(block.terminator));
      }
      if (!block.successors.isEmpty()) {
        out.print("  Successors:");
        for (Block successor : block.successors) {
          out.print(" B" + successor.id);
        }
        out.println();
      }
    }
    out.println();
  }

  private static String syntaxNodeToDebugString(Tree syntaxNode) {
    StringBuilder sb = new StringBuilder(syntaxNode.kind().name())
      .append(' ').append(Integer.toHexString(syntaxNode.hashCode()));
    switch (syntaxNode.kind()) {
      case IDENTIFIER:
        sb.append(' ').append(((IdentifierTree) syntaxNode).identifierToken().text());
        break;
      case INT_LITERAL:
        sb.append(' ').append(((LiteralTree) syntaxNode).token().text());
        break;
    }
    return sb.toString();
  }

}
