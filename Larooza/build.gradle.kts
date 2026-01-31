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
        // targetSdk är bara en varning i library-moduler, men okej att ha kvar
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * ✅ Ny DSL (ersätter kotlinOptions) + fixar -jvm-default=all problemet
 * Din Kotlin accepterar: [disable, enable, no-compatibility]
 */
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        jvmDefault.set(JvmDefaultMode.ENABLE)
        allWarningsAsErrors.set(false)
    }
}

/**
 * NOTE:
 * - Vi inkluderar cloudstream-blocket bara som metadata.
 * - Om cloudstream-plugin inte finns i detta repo så ignoreras blocket av Gradle.
 * - Det påverkar INTE Kotlin-kompileringen.
 */
@Suppress("UNUSED_EXPRESSION")
cloudstream {
    description = "Cima4U provider"
    authors = listOf("FathedAbOss")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}
