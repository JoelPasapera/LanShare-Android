// AGP 9 compila Kotlin por sí mismo ("Kotlin integrado"): :app no aplica el
// plugin de Kotlin. Declarar aquí la versión de KGP fija la que usan los dos
// módulos (AGP tomaría la suya, 2.2.10, si no se indica otra mayor).
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
}
