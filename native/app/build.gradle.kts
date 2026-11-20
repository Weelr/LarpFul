plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.weelr.larpful2"
    compileSdk = 34

    // -Pmain=1 builds the app that installs over the first Larpful (same package, same signing key, higher version)
    val asMain = project.hasProperty("main")
    defaultConfig {
        applicationId = if (asMain) "com.larpful.notes" else "com.weelr.larpful2"
        manifestPlaceholders["appLabel"] = "Larpful"
        minSdk = 29
        targetSdk = 34
        // +1000: this project moved here from the Larpful-V2 repo, so builds must keep installing over the older ones
        versionCode = 1000 + (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "2.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = if (project.hasProperty("main")) rootProject.file("../resources/debug.keystore") else rootProject.file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-Xjvm-default=all")
    }
    packaging {
        resources { excludes += setOf("META-INF/**") }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                it.testLogging {
                    events("passed", "failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                    showStandardStreams = true
                }
            }
        }
    }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
