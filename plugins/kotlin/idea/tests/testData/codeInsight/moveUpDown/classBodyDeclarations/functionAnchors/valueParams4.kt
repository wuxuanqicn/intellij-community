// MOVE: up
// MOVER_CLASS: org.jetbrains.jet.plugin.codeInsight.upDownMover.JetExpressionMover
class A {
    fun foo<T,
            U,
            W>(
            b: Int,
            c: Int
            <caret>a: Int,
    ) {

    }
    class B {

    }
}