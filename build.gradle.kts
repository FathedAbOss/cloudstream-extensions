import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()

        // ✅ JitPack repo (NO token needed for public dependencies)
        maven { url = uri("https://jitpack.io") }
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.2.0")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()

        // ✅ JitPack repo (NO token needed for public dependencies)
        maven { url = uri("https://jitpack.io") }
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    // ✅ VERIFIED VERSION: "pre-release" points to the Legacy API (v4 compatibility)
    val cloudstream_version = "pre-release"

    configure<BaseExtension> {
        namespace = "com.fathedaboss.${project.name.lowercase()}"
        compileSdkVersion(34)

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    extensions.configure<CloudstreamExtension> {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    dependencies {
        // ✅ FIX: correct dependency coordinates
        add("compileOnly", "com.lagradost:cloudstream3:$cloudstream_version")

        add("implementation", "org.jsoup:jsoup:1.15.3")
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xno-param-assertions",
                "-Xjvm-default=all"
            )
        }
    }
}

task<Delete>("clean") {
    delete(layout.buildDirectory)
}

task("makeAllPlugins") {
    subprojects.forEach { sub ->
        dependsOn(sub.tasks.matching { it.name == "make" })
    }
}
