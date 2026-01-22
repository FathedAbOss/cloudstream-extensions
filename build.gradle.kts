import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    // Configure Android immediately to avoid "compileSdkVersion not specified"
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

    // Configure Cloudstream
    extensions.configure<CloudstreamExtension> {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    // Configure Dependencies
    dependencies {
        val cloudstream_version = "master-SNAPSHOT"
        add("compileOnly", "com.github.recloudstream:cloudstream3:$cloudstream_version")
        add("implementation", "org.jsoup:jsoup:1.15.3")
        add("implementation", "com.github.recloudstream:nicehttp:master-SNAPSHOT")
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }
}

// --- ROOT TASKS ---
task<Delete>("clean") {
    delete(layout.buildDirectory)
}

// These tasks are already provided by the Cloudstream plugin in subprojects.
// We just need a root task to trigger them all.
task("makeAllPlugins") {
    subprojects.forEach { sub ->
        dependsOn(sub.tasks.matching { it.name == "make" })
    }
}

// The Cloudstream plugin already provides 'makePluginsJson' in the root project
// if applied correctly, but we'll ensure it's triggered.
