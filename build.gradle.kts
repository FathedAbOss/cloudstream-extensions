import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io" )
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // Use a stable version of the gradle plugin instead of -SNAPSHOT
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io" )
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = 
    extensions.getByType<CloudstreamExtension>().configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = 
    extensions.getByType<BaseExtension>().configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // This automatically sets the repo for the plugins.json
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    android {
        namespace = "com.fathedaboss.${project.name.lowercase()}"
        compileSdk = 34

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }

    dependencies {
        // Use a specific commit hash or a stable tag instead of master-SNAPSHOT to avoid 401 errors
        val cloudstream_version = "master-SNAPSHOT" 
        compileOnly("com.github.recloudstream:cloudstream3:$cloudstream_version")
        
        implementation("org.jsoup:jsoup:1.15.3")
        implementation("com.github.recloudstream:nicehttp:master-SNAPSHOT" )
    }
}
