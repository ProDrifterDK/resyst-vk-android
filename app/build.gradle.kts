plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.resyst.vk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.resyst.vk"
        minSdk = 26
        targetSdk = 36
        // -Pvk.versionCode=N / -Pvk.versionName=X: local test builds only (updater E2E), never published.
        versionCode = (findProperty("vk.versionCode") as String?)?.toInt() ?: 9
        versionName = (findProperty("vk.versionName") as String?) ?: "0.8.0"
        // r11b (GK1–GK3): the KLIPY API key is read at build time from keystore/klipy.key
        // (gitignored, like the signing secrets). No file, or anything but a plain token, means
        // an empty key: the build still succeeds and the GIF search is hidden entirely.
        val klipyFile = rootProject.file("keystore/klipy.key")
        val klipyKey = if (klipyFile.exists()) klipyFile.readText().trim().takeIf { Regex("[A-Za-z0-9_-]{16,128}").matches(it) } ?: "" else ""
        buildConfigField("String", "KLIPY_KEY", "\"$klipyKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            // keystore/ is gitignored; CI or a fresh clone provides it out of band.
            val ksFile = rootProject.file("keystore/resyst-vk-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                val passFile = rootProject.file("keystore/store.pass")
                storePassword = passFile.readText().trim()
                keyAlias = "resyst-vk"
                keyPassword = passFile.readText().trim()
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // r8 lexicon eval knobs (LexiconEvalTest): -Pvk.lexDir=… -Pvk.evalTag=… -Pvk.evalOnly
        unitTests.all { t ->
            listOf("vk.lexDir", "vk.evalTag", "vk.evalOnly").forEach { k ->
                project.findProperty(k)?.let { t.systemProperty(k, it.toString()) }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ExploreByTouchHelper (TalkBack virtual keys). The only runtime dependency.
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.core:core:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
