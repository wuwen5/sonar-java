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

import com.google.common.base.Charsets;
import com.sonar.sslr.api.typed.ActionParser;
import org.junit.Test;
import org.sonar.java.ast.parser.JavaParser;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;

import static org.fest.assertions.Assertions.assertThat;

public class CFGTest {

  public static final ActionParser<Tree> parser = JavaParser.createParser(Charsets.UTF_8);

  private static CFG buildCFG(String methodCode) {
    CompilationUnitTree cut = (CompilationUnitTree) parser.parse("class A { "+methodCode+" }");
    MethodTree tree = ((MethodTree) ((ClassTree) cut.types().get(0)).members().get(0));
    return CFG.build(tree);
  }

  @Test
  public void simplest_cfg() throws Exception {
    CFG cfg = buildCFG("void fun() {}");
    assertThat(cfg.blocks).hasSize(2);
    cfg = buildCFG("void fun() { bar();}");
    assertThat(cfg.blocks).hasSize(2);
    cfg = buildCFG("void fun() { bar();qix();baz();}");
    assertThat(cfg.blocks).hasSize(2);
  }

  @Test
  public void cfg_local_variable() throws Exception {
    CFG cfg = buildCFG("void fun() {Object o;}");
    assertThat(cfg.blocks).hasSize(2);
    assertThat(cfg.blocks.get(0).elements).isEmpty();
    assertThat(cfg.blocks.get(1).elements).hasSize(1);
  }

  @Test
  public void cfg_if_statement() throws Exception {
    CFG cfg = buildCFG("void fun() {if(a) { foo(); } }");
    assertThat(cfg.blocks).hasSize(4);
    assertThat(successors(cfg.blocks.get(1))).containsOnly(0);
    assertThat(successors(cfg.blocks.get(2))).containsOnly(1);
    assertThat(successors(cfg.blocks.get(3))).containsOnly(1, 2);
    assertThat(cfg.blocks.get(3).terminator).isNotNull();
    assertThat(cfg.blocks.get(3).elements).hasSize(1);
    assertThat(cfg.blocks.get(3).terminator.is(Tree.Kind.IF_STATEMENT)).isTrue();

    cfg = buildCFG("void fun() {if(a) { foo(); } else { bar(); } }");
    assertThat(cfg.blocks).hasSize(5);
    assertThat(successors(cfg.blocks.get(1))).containsOnly(0);
    assertThat(successors(cfg.blocks.get(2))).containsOnly(1);
    assertThat(successors(cfg.blocks.get(3))).containsOnly(1);
    assertThat(successors(cfg.blocks.get(4))).containsOnly(2, 3);
    assertThat(cfg.blocks.get(4).terminator).isNotNull();
    assertThat(cfg.blocks.get(4).elements).hasSize(1);
    assertThat(cfg.blocks.get(3).elements).hasSize(2);
    assertThat(cfg.blocks.get(2).elements).hasSize(2);
    assertThat(cfg.blocks.get(4).terminator.is(Tree.Kind.IF_STATEMENT)).isTrue();

    cfg = buildCFG("void fun() {\nif(a) {\n foo(); \n } else if(b) {\n bar();\n } }");
    assertThat(cfg.blocks).hasSize(6);
    assertThat(cfg.blocks.get(5).terminator.is(Tree.Kind.IF_STATEMENT)).isTrue();
    assertThat(cfg.blocks.get(3).terminator.is(Tree.Kind.IF_STATEMENT)).isTrue();
  }

  @Test
  public void conditional_or_and() throws Exception {
    CFG cfg = buildCFG("void fun() {if(a || b) { foo(); } }");
    assertThat(cfg.blocks).hasSize(5);
    assertThat(cfg.blocks.get(4).terminator.is(Tree.Kind.CONDITIONAL_OR)).isTrue();
    assertThat(cfg.blocks.get(3).terminator.is(Tree.Kind.IF_STATEMENT)).isTrue();

    cfg = buildCFG("void fun() {if((a && b)) { foo(); } }");
    assertThat(cfg.blocks).hasSize(5);
    assertThat(cfg.blocks.get(4).terminator.is(Tree.Kind.CONDITIONAL_AND)).isTrue();
    assertThat(cfg.blocks.get(3).terminator.is(Tree.Kind.IF_STATEMENT)).isTrue();
  }

  @Test
  public void conditional_expression() throws Exception {
    CFG cfg = buildCFG("void fun() { foo ? a : b; a.toString();}");
    assertThat(cfg.blocks).hasSize(5);
    assertThat(cfg.blocks.get(4).terminator.is(Tree.Kind.CONDITIONAL_EXPRESSION)).isTrue();
  }

  private static int[] successors(CFG.Block block) {
    int[] successors = new int[block.successors.size()];
    for (int i = 0; i < block.successors.size(); i++) {
      successors[i] = block.successors.get(i).id;
    }
    return successors;
  }




}
