plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * Release signing comes from the environment, so the key never lives in the repository. CI sets
 * these from repository secrets; locally they are simply absent, and an unsigned release APK is
 * built instead of the build failing. Debug builds are unaffected either way.
 */
val keystoreFile: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
val keystoreKeyAlias: String? = System.getenv("KEY_ALIAS")
val keystoreKeyPassword: String? = System.getenv("KEY_PASSWORD")
val canSignRelease = !keystoreFile.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    !keystoreKeyAlias.isNullOrBlank() &&
    !keystoreKeyPassword.isNullOrBlank()

android {
    namespace = "com.aidley.uvwidget"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aidley.uvwidget"
        minSdk = 26
        targetSdk = 35
        // Bump both when releasing: the tag must match versionName, and versionCode is what
        // Android compares when deciding whether one build is newer than another.
        versionCode = 2
        versionName = "1.0.1"
    }

    buildFeatures {
        // For the version shown at the bottom of the settings screen.
        buildConfig = true
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSignRelease) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
