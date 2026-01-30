// Larooza/build.gradle.kts
// (Full file version that avoids deprecated kotlinOptions/freeCompilerArgs and uses compilerOptions DSL)

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

cloudstream {
    description = "Cima4U (cfu.cam) provider"
    authors = listOf("FathedAbOss")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        // Do NOT fail the build on warnings in this module
        allWarningsAsErrors.set(false)

        // If some global config injects -Werror, strip it here
        freeCompilerArgs.set(
            freeCompilerArgs.get().filterNot { it == "-Werror" }
        )
    }
}
