import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.example"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }
}
version = 1

cloudstream {
       // Set the package name of your provider
    setPackage("com.example")
    // Set the class name of your provider
    setProviderClass("FarfeshPlusProvider")

    // Plugin metadata
    description = "FarfeshPlus placeholder provider"
    authors = listOf("FathedAbOss")
    status = 1
    language = "ar"
    tvTypes = listOf("Movie", "TvSeries")
    iconUrl = "https://raw.githubusercontent.com/recloudstream/cloudstream/master/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png"

dependencies {
    val cloudstream_version = "3.0.0"
    compileOnly("com.lagradost:cloudstream3:$cloudstream_version")
}
