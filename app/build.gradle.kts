plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "jp.sohapps.vlccompanion"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "jp.sohapps.vlccompanion"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("jp.sohapps.sohplayerkit:companion-contract:0.1.0-SNAPSHOT")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.libvlc)
}
