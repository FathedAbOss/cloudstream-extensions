import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ✅ New DSL instead of kotlinOptions
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // keep warnings as warnings (not errors)
        allWarningsAsErrors.set(false)
        // remove -Werror if any parent script adds it
        freeCompilerArgs.addAll(listOf("-Xjvm-default=all"))
    }
}

cloudstream {
    description = "Cima4U provider"
    authors = listOf("FathedAbOss")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}
