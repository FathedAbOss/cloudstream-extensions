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

cloudstream {
    // Set the package name of your provider
    setPackage("com.example")
    // Set the class name of your provider
    setProviderClass("YallaCimaProvider")
}

dependencies {
    val cloudstream_version = "3.0.0"
    compileOnly("com.lagradost:cloudstream3:$cloudstream_version")
}
