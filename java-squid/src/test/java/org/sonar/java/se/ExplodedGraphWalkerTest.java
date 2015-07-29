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
package org.sonar.java.se;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.sonar.sslr.api.typed.ActionParser;
import org.apache.commons.collections.ListUtils;
import org.apache.commons.io.Charsets;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.sonar.java.ast.parser.JavaParser;
import org.sonar.java.resolve.SemanticModel;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.CompilationUnitTree;
import org.sonar.plugins.java.api.tree.Tree;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.fest.assertions.Assertions.assertThat;
import static org.fest.assertions.Fail.fail;

public class ExplodedGraphWalkerTest {

  ByteArrayOutputStream out;

  @Before
  public void setUp() throws Exception {
    out = new ByteArrayOutputStream();
  }

  @Test
  public void test() throws Exception {
    getGraphWalker("class A  { Object a; void func() { if(a==null)\n a.toString();\n } } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 2");
  }

  @Test
  public void test_complex_condition() throws Exception {
    getGraphWalker("class A  \n{ Object a;\n void func() \n{ if(b == a &&\n a == null) \na.toString();\n } } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 6");
  }

  @Test
  public void test_complex_condition_inverted() throws Exception {
    getGraphWalker("class A  { Object a; void func() { if(null == a && b == a) \na.toString();\n } } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 2");
  }

  @Test
  public void test_reassignement() throws Exception {
    getGraphWalker("class A  { Object a; Object b; void func() { if(b == null) {\na = b; \n a.toString();\n }} } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 3");
  }

  @Test
  public void test_null_assignement() throws Exception {
    getGraphWalker("class A  { void func(Object a) { a = null;\n a.toString();\n } } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 2");
  }

  @Test
  public void local_variable() throws Exception {
    getGraphWalker("class A  { \nvoid func() {\n Object a;\n a.toString();\n }\n } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 4");
  }

  @Test
  public void tracking_symbolic_value() throws Exception {
    getGraphWalker("class A  { \nvoid func() {\n Object a = null; Object b = new Object(); b = a; if( b == null) {\n a.toString();}\n }\n } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 4");
  }

  @Test
  public void test_assign_null() throws Exception {
    ExplodedGraphWalker graphWalker = getGraphWalker("class A  { \nvoid func(Object a) {\n if(a != null){ a = null;\n a.toString();\n }}\n } ");
    //Only two steps as we sink into the second because of the NPE.
    assertThat(graphWalker.steps).isEqualTo(11);
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 4");
  }

  @Test
  public void condition_always_false() throws Exception {
    getGraphWalker("class A  {void func(Object a){ a = null; if(\na == null\n);}}");
    String output = out.toString();
    assertThat(output).contains("condition at line 2 always evaluate to false");
  }

  @Test
  public void condition_always_true() throws Exception {
    getGraphWalker("class A  {void func(Object a){ a = null; Object b= null; if(\na != null && b!=null\n);}}");
    String output = out.toString();
    assertThat(output).contains("condition at line 2 always evaluate to true");
  }

  @Test
  public void test_null_pointer_check_unit_test() throws Exception {
    Pattern pattern = Pattern.compile("Null pointer dereference at line (\\d*)");

    List<String> unitTestNPE = Files.readLines(new File("../java-checks/src/test/files/checks/NullPointerCheck.java"), Charsets.UTF_8);
    List<Integer> expectedLines = Lists.newArrayList();
    int lineNb = 0;
    for (String line : unitTestNPE) {
      lineNb++;
      if(line.contains("// Noncompliant")) {
        expectedLines.add(lineNb);
      }
    }
    getGraphWalker(Joiner.on("\n").join(unitTestNPE));
    String output = out.toString();
    Matcher matcher = pattern.matcher(output);
    List<Integer> issueRaised = Lists.newArrayList();
    while (matcher.find()) {
      issueRaised.add(Integer.valueOf(matcher.group(1)));
    }
    issueRaised = Lists.newArrayList(Sets.newTreeSet(issueRaised));


    List falseNegatives = ListUtils.subtract(expectedLines, issueRaised);
    List falsePositives = ListUtils.subtract(issueRaised, expectedLines);
    Collections.sort(falseNegatives);
    String error = "";
    if(!falseNegatives.isEmpty()) {
      error += "\nFalse negative at lines : "+Joiner.on(", ").join(falseNegatives)+"\n";
    }
    if(!falsePositives.isEmpty()) {
      error += "False positives at lines : "+Joiner.on(", ").join(falsePositives);
    }
    if(!error.isEmpty()) {
      fail(error);
    }

  }

  @Test
  public void test_npe_in_conditional_and() throws Exception {
    getGraphWalker("class A  { \nvoid func(Object a) {\n boolean b1 = str == null && str.length() == 0;}\n } ");
    String output = out.toString();
    assertThat(output).contains("Null pointer dereference at line 3");
  }

  private ExplodedGraphWalker getGraphWalker(String source) {
    ActionParser<Tree> parser = JavaParser.createParser(Charsets.UTF_8);
    CompilationUnitTree cut = (CompilationUnitTree) parser.parse(source);
    SemanticModel.createFor(cut, Lists.<File>newArrayList());
    ExplodedGraphWalker graphWalker = new ExplodedGraphWalker(new PrintStream(out), Mockito.mock(JavaFileScannerContext.class));
    cut.accept(graphWalker);
    return graphWalker;
  }


}