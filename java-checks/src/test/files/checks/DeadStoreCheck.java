class A {

  abstract int foo();

  int foo(int u) {
    int x = 0;// Noncompliant {{Remove this useless assignment to local variable "x".}}
    x = 3;
    int y = x + 1; // Noncompliant {{Remove this useless assignment to local variable "y".}}
    x = 2; // Noncompliant {{Remove this useless assignment to local variable "x".}}
    x = 3;
    y = 2;
    foo(y);
    foo(x);
    Object a = new Object();
    System.out.println(a);
    a = null; // Noncompliant
  }

}