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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    // Kotlin 2.x: kotlinOptions DSL is not allowed -> use compilerOptions DSL
    compilerOptions {
        // keep warnings as warnings (not errors)
        allWarningsAsErrors.set(false)

        // remove -Werror if some upstream adds it
        val current = freeCompilerArgs.getOrElse(emptyList())
        freeCompilerArgs.set(current.filterNot { it == "-Werror" })

        // keep JVM target consistent with repo (works fine for CS extensions)
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

cloudstream {
    description = "Cima4U provider"
    authors = listOf("FathedAbOss")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}
