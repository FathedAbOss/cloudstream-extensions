import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

cloudstream {
    description = "Cima4U provider"
    authors = listOf("FathedAbOss")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}

android {
    namespace = "com.example"
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(false)
        freeCompilerArgs.set(freeCompilerArgs.get().filterNot { it == "-Werror" })
    }
}
