plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zgo.arecordplaypcm"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zgo.arecordplaypcm"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }


    signingConfigs {
        register("release") {
            keyAlias = "ipoc_key"
            keyPassword = "654321"
            storeFile = file("./ipoc_key")
            storePassword = "123456"
        }
    }

    buildTypes {
        debug {
            signingConfig  = signingConfigs["release"]
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        release {
            signingConfig  = signingConfigs["release"]
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

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}