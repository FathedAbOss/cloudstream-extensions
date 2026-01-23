import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }

    dependencies {
        // ✅ AGP must be >= 8.2.2
        classpath("com.android.tools.build:gradle:8.2.2")

        // ✅ Cloudstream gradle plugin
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")

        // ✅ Kotlin version MUST match Cloudstream libs (2.3.x)
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
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    // ✅ Fix "unspecified" warning
    version = 1

    configure<BaseExtension> {
        namespace = "com.fathedaboss.${project.name.lowercase()}"

        compileSdkVersion(34)

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        // ✅ Java MUST match Kotlin (use 17)
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    // ✅ Kotlin MUST match Java target (17)
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    extensions.configure<CloudstreamExtension> {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    dependencies {
        // ✅ IMPORTANT FIX:
        // This is the correct Cloudstream API library the plugins need
        add("compileOnly", "com.github.recloudstream:cloudstream:master-SNAPSHOT")

        // your other libs
        add("implementation", "org.jsoup:jsoup:1.15.3")
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
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
