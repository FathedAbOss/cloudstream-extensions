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

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = 
    extensions.getByType<CloudstreamExtension>().configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = 
    extensions.getByType<BaseExtension>().configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    // Use afterEvaluate to ensure the android extension is available
    afterEvaluate {
        if (project.extensions.findByName("android") != null) {
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
        }
        
        if (project.extensions.findByName("dependencies") != null) {
            dependencies {
                val cloudstream_version = "master-SNAPSHOT"
                add("compileOnly", "com.github.recloudstream:cloudstream3:$cloudstream_version")
                add("implementation", "org.jsoup:jsoup:1.15.3")
                add("implementation", "com.github.recloudstream:nicehttp:master-SNAPSHOT")
            }
        }
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = "1.8"
            freeCompilerArgs = freeCompilerArgs + "-Xjvm-default=all"
        }
    }
}
