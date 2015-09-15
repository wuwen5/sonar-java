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
import org.sonar.java.resolve.SemanticModel;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonar.plugins.java.api.tree.MethodTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.sslr.tests.Assertions;

import java.io.File;
import java.util.Collections;

import static org.fest.assertions.Assertions.assertThat;

public class LiveVariablesTest {

  public static final ActionParser<Tree> PARSER = JavaParser.createParser(Charsets.UTF_8);

  private static CFG buildCFG(String methodCode) {
    CompilationUnitTree cut = (CompilationUnitTree) PARSER.parse("class A { int field; " + methodCode + " }");
    SemanticModel.createFor(cut, Collections.<File>emptyList());
    MethodTree tree = ((MethodTree) ((ClassTree) cut.types().get(0)).members().get(1));
    return CFG.build(tree);
  }

  @Test
  public void test_simple_live() {
    CFG cfg = buildCFG("void foo(int a) {  int i; /* should be live here */ if (false) ; foo(i); }");
    LiveVariables liveVariables = LiveVariables.analyze(cfg);
    assertThat(liveVariables.getOut(cfg.blocks.get(3))).hasSize(1);
    assertThat(liveVariables.getOut(cfg.blocks.get(3)).iterator().next().name()).isEqualTo("i");
  }

  @Test
  public void test_simple_death() throws Exception {
    CFG cfg = buildCFG("void foo(int a) {  int i; /* should not be live here */ if (false) ; i = 0; }");
    LiveVariables liveVariables = LiveVariables.analyze(cfg);
    assertThat(liveVariables.getOut(cfg.blocks.get(3))).isEmpty();
  }

  @Test
  public void test_field_not_tracked() throws Exception {
    CFG cfg = buildCFG("void foo(int a) { field = 0; /* fields should not be tracked */ if (false) ; foo(field); }");
    LiveVariables liveVariables = LiveVariables.analyze(cfg);
    assertThat(liveVariables.getOut(cfg.blocks.get(3))).isEmpty();
    cfg = buildCFG("void foo(int a) { a = 0; /* but arguments should be tracked */ if (false) ; foo(a); }");
    liveVariables = LiveVariables.analyze(cfg);
    assertThat(liveVariables.getOut(cfg.blocks.get(3))).hasSize(1);
    assertThat(liveVariables.getOut(cfg.blocks.get(3)).iterator().next().name()).isEqualTo("a");
  }

  @Test
  public void test_while_loop() throws Exception {
    CFG cfg = buildCFG("void foo(boolean condition) { while (condition) { int x = 0; use(x); x = 1; /* x should not be live here*/}}");
    cfg.debugTo(System.out);
    LiveVariables liveVariables = LiveVariables.analyze(cfg);
    liveVariables.debugTo(System.out);
    assertThat(liveVariables.getOut(cfg.blocks.get(3))).hasSize(1);
    assertThat(liveVariables.getOut(cfg.blocks.get(4))).hasSize(1);
  }

  @Test
  public void in_of_first_block_should_be_empty() throws Exception {
    CFG cfg = buildCFG("boolean foo(int a) { foo(a);}");
    LiveVariables liveVariables = LiveVariables.analyze(cfg);
    assertThat(liveVariables.getOut(cfg.blocks.get(0))).isEmpty();
    assertThat(liveVariables.getOut(cfg.blocks.get(1))).isEmpty();
  }
}
