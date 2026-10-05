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
        versionCode = (findProperty("vk.versionCode") as String?)?.toInt() ?: 2
        versionName = (findProperty("vk.versionName") as String?) ?: "0.2.0"
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
