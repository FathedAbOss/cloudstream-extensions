import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            credentials {
                username = System.getenv("JITPACK_TOKEN")
            }
        }
    }

    dependencies {
        // KEEP: Valid Gradle version
        classpath("com.android.tools.build:gradle:8.2.0") 
        // KEEP: Cloudstream Gradle Plugin
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        // CRITICAL FIX: Downgraded Kotlin from 2.3.0 to 1.9.24 to match Legacy Code
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            credentials {
                username = System.getenv("JITPACK_TOKEN")
            }
        }
    }
}

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    configure<BaseExtension> {
        // KEEP: Your dynamic namespace logic
        namespace = "com.fathedaboss.${project.name.lowercase()}"
        compileSdkVersion(34)

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        // CRITICAL FIX: Downgraded Java from 17 to 1.8 (Standard for Legacy Plugins)
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    extensions.configure<CloudstreamExtension> {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    dependencies {
        // CRITICAL FIX: Replaced 'master-SNAPSHOT' with a fixed Legacy Commit Hash
        // This hash points to a version where 'Plugin()' class still exists.
        val cloudstream_version = "af72363" 
        
        add("compileOnly", "com.github.recloudstream:cloudstream:$cloudstream_version")
        add("implementation", "org.jsoup:jsoup:1.15.3")
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    }

    tasks.withType<KotlinCompile> {
        // CRITICAL FIX: Switched to 'kotlinOptions' (Legacy DSL) and Target 1.8
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + "-Xno-param-assertions"
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
