import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

android {
    namespace = "com.fathedaboss.wecima"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}

version = "1.0.0"

cloudstream {
    language = "ar"
    description = "WeCima provider"
    authors = listOf("FathedAbOss")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
}

dependencies {
    val cloudstream_version = "master-SNAPSHOT"
    compileOnly("com.github.recloudstream:cloudstream3:$cloudstream_version")
    implementation("org.jsoup:jsoup:1.15.3")
    implementation("com.github.recloudstream:nicehttp:master-SNAPSHOT" )
}
