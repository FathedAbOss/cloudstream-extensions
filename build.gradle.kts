import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }

    dependencies {
        // ✅ AGP (Android Gradle Plugin)
        classpath("com.android.tools.build:gradle:8.2.2")

        // ✅ Cloudstream Gradle plugin
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")

        // ✅ IMPORTANT: Kotlin must match Cloudstream (now Kotlin 2.3.0)
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }
}

subprojects {
    // ✅ removes "'unspecified' is not a valid version. Use an integer."
    version = 1

    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    configure<BaseExtension> {
        namespace = "com.fathedaboss.${project.name.lowercase()}"

        compileSdkVersion(34)

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        // ✅ Java = 1.8
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    extensions.configure<CloudstreamExtension> {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    dependencies {
        // ✅ Cloudstream API (needed for Plugin/MainAPI/registerMainAPI)
        add("compileOnly", "com.github.recloudstream:cloudstream:master-SNAPSHOT")

        add("compileOnly", "com.github.Blatzar:CloudstreamApi:0.1.7")
        add("implementation", "org.jsoup:jsoup:1.15.3")
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    }

    // ✅ Kotlin: use compilerOptions (NEW DSL)
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

tasks.register("makeAllPlugins") {
    subprojects.forEach { sub ->
        dependsOn(sub.tasks.matching { it.name == "make" })
    }
}
