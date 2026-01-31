import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.lagradost.cloudstream3.plugin")
}

android {
    namespace = "com.example"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * ✅ NEW DSL (fixar både kotlinOptions-felet och -jvm-default=all-felet)
 *
 * Din Kotlin accepterar: [disable, enable, no-compatibility]
 * Så vi använder ENABLE (motsvarar i praktiken "all" i äldre setup).
 */
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)

        // Viktigt: byt bort "all" -> "enable"
        jvmDefault.set(JvmDefaultMode.ENABLE)

        // Om repo:n tidigare tvingade Werror i någon modul så neutraliserar vi det här
        allWarningsAsErrors.set(false)
    }
}

cloudstream {
    description = "Cima4U provider"
    authors = listOf("FathedAbOss")
    language = "ar"
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}
