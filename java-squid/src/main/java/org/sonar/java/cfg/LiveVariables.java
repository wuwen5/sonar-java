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

import com.google.common.collect.Sets;
import org.sonar.plugins.java.api.semantic.Symbol;
import org.sonar.plugins.java.api.tree.AssignmentExpressionTree;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.IdentifierTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.VariableTree;

import java.io.PrintStream;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

public class LiveVariables {

  private final CFG cfg;
  private final Map<CFG.Block, Set<Symbol>> in = new HashMap<>();
  private final Map<CFG.Block, Set<Symbol>> out = new HashMap<>();

  private LiveVariables(CFG cfg) {
    this.cfg = cfg;
  }

  public Set<Symbol> getOut(CFG.Block block) {
    return out.get(block);
  }

  public static LiveVariables analyze(CFG cfg) {
    LiveVariables liveVariables = new LiveVariables(cfg);

    Map<CFG.Block, Set<Symbol>> kill = new HashMap<>();
    Map<CFG.Block, Set<Symbol>> gen = new HashMap<>();
    for (CFG.Block block : cfg.blocks) {
      Set<Symbol> blockKill = new HashSet<>();
      Set<Symbol> blockGen = new HashSet<>();

      // process elements from bottom to top
      Set<Tree> assignmentLHS = new HashSet<>();
      for (Tree element : block.elements) {
        Symbol symbol;
        switch (element.kind()) {
          case ASSIGNMENT:
            ExpressionTree lhs = ((AssignmentExpressionTree) element).variable();
            if (lhs.is(Tree.Kind.IDENTIFIER)) {
              symbol = ((IdentifierTree) lhs).symbol();
              if (isLocalVariable(symbol)) {
                if (symbol.isUnknown()) {
                  throw new IllegalStateException("Local variable " + ((IdentifierTree) lhs).name() + " is unknown.");
                }
                assignmentLHS.add(lhs);
                blockGen.remove(symbol);
                blockKill.add(symbol);
              }
            }
            break;
          case IDENTIFIER:
            if (!assignmentLHS.contains(element)) {
              symbol = ((IdentifierTree) element).symbol();
              if (isLocalVariable(symbol)) {
                if (symbol.isUnknown()) {
                  throw new IllegalStateException("Local variable " + ((IdentifierTree) element).name() + " is unknown.");
                }
                blockGen.add(symbol);
              }
            }
            break;
          case VARIABLE:
            blockGen.remove(((VariableTree) element).symbol());
            break;

        }
      }
      kill.put(block, blockKill);
      gen.put(block, blockGen);
    }

    Deque<CFG.Block> workList = new LinkedList<>();
    workList.addAll(cfg.blocks);
    while (!workList.isEmpty()) {
      CFG.Block block = workList.removeFirst();

      Set<Symbol> out = liveVariables.out.get(block);
      if (out == null) {
        out = new HashSet<>();
        liveVariables.out.put(block, out);
      }
      for (CFG.Block successor : block.successors) {
        Set<Symbol> inOfSuccessor = liveVariables.in.get(successor);
        if (inOfSuccessor != null) {
          out.addAll(inOfSuccessor);
        }
      }

      // in = gen and (out - kill)
      Set<Symbol> newIn = new HashSet<>(gen.get(block));
      newIn.removeAll(Sets.difference(out, kill.get(block)));

      if (newIn.equals(liveVariables.in.get(block))) {
        continue;
      }
      liveVariables.in.put(block, newIn);
      for (CFG.Block predecessor : block.predecessors) {
        workList.addLast(predecessor);
      }
    }

    // in of first block and out of exit block are empty by definition.
    if (!liveVariables.in.get(cfg.blocks.get(cfg.blocks.size() - 1)).isEmpty()) {
      throw new IllegalStateException("In of first block should be empty");
    }
    if(!liveVariables.out.get(cfg.blocks.get(0)).isEmpty()) {
      throw new IllegalStateException("Out of exit block should be empty");
    }
    return liveVariables;
  }

  private static boolean isLocalVariable(Symbol symbol) {
    return symbol.owner().isMethodSymbol();
  }

  public void debugTo(PrintStream out) {
    for (CFG.Block block : cfg.blocks) {
      out.println("B" + block.id + " Live:");
      for (Symbol live : this.out.get(block)) {
        out.print(" " + live.name() + "@" + Integer.toHexString(live.hashCode()));
      }
      out.println();
    }
  }

}
