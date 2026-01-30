import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

cloudstream {
    description = "Cima4U (cfu.cam) provider"
    authors = listOf("FathedAbOss")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}

/**
 * CI FIX: do not fail build on warnings for this module
 */
tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        allWarningsAsErrors = false
        freeCompilerArgs = freeCompilerArgs.filterNot { it == "-Werror" }
    }
}
