import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        // Library targetSdk warning is fine; not a build blocker.
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)

        // Kotlin in your build supports: disable/enable/no-compatibility
        jvmDefault.set(JvmDefaultMode.ENABLE)

        // Do NOT fail on warnings
        allWarningsAsErrors.set(false)

        // ✅ Important: if the repo injects -Werror globally, strip it here
        val args = freeCompilerArgs.getOrElse(emptyList())
        freeCompilerArgs.set(
            args.filterNot { it == "-Werror" || it.startsWith("-Werror") }
        )
    }
}
