import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Compilar contra la API de Java 17 aunque Gradle corra con un JDK más
        // nuevo: un método de Java 21 (List.removeFirst, por ejemplo) se colaría
        // sin aviso y fallaría en los Android que no lo tienen.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// Sin dependencias: ni siquiera para las pruebas. La batería trae su propio
// ejecutor (src/test/.../testkit), como la de LanShare para Windows.
val runTests by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Ejecuta la batería de pruebas propia del núcleo (sin frameworks externos)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.joelpasapera.lanshare.core.AllTestsKt")
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// La tarea "test" estándar busca JUnit y no lo hay: se sustituye por runTests.
tasks.named<Test>("test") {
    enabled = false
}

tasks.named("check") {
    dependsOn(runTests)
}
