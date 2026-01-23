import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://www.jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")
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

    configure<BaseExtension> {
        namespace = "com.fathedaboss.${project.name.lowercase()}"

        compileSdkVersion(34)

        defaultConfig {
            minSdk = 21
            targetSdk = 34
        }

        // Java 1.8
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    extensions.configure<CloudstreamExtension> {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "FathedAbOss/cloudstream-extensions")
    }

    dependencies {
        // ✅ IMPORTANT FIX: This gives Plugin + registerMainAPI
        add("compileOnly", "com.github.recloudstream:cloudstream:master-SNAPSHOT")

        add("implementation", "org.jsoup:jsoup:1.15.3")
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
    }

    // ✅ Kotlin must match Java 1.8
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
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
