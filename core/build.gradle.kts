plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // The evaluation report prints tables; show them when run with --info or -Peval.
    testLogging { showStandardStreams = project.hasProperty("eval") }
    systemProperty("assets.dir", rootProject.file("app/src/main/assets").absolutePath)
    systemProperty("lexicon.dir", rootProject.file("lexicon").absolutePath)
    systemProperty("report.dir", rootProject.file("docs").absolutePath)
    maxHeapSize = "2g"
}

// Compiles lexicon/*.tsv into the compact binary assets the keyboard loads.
tasks.register<JavaExec>("generateLexicon") {
    group = "lucid"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.lucid.keyboard.core.lm.LexiconCompilerKt")
    args(rootProject.file("lexicon").absolutePath, rootProject.file("app/src/main/assets").absolutePath)
}
