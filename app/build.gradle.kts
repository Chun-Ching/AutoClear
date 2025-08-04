plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "tw.thinkingsoftware.powerclear"
    compileSdk = 35

    defaultConfig {
        applicationId = "tw.thinkingsoftware.powerclear"
        minSdk = 24
        //noinspection EditedTargetSdkVersion
        targetSdk = 35
        versionCode = 1
        versionName = "1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Kotlin DSL 中，使用 create 方法來定義新的 signingConfigs
        create("release") {
            storeFile(file(System.getenv("KEYSTORE_FILE_PATH")))
            storePassword(System.getenv("KEYSTORE_PASSWORD"))
            keyAlias(System.getenv("KEY_ALIAS"))
            keyPassword(System.getenv("KEY_PASSWORD"))
        }
    }

    buildTypes {
        // Kotlin DSL 中，使用 getByName 方法來存取已有的 build type
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 在 Kotlin DSL 中，存取 signingConfigs 需使用 getByName 方法
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 這幾個區塊必須在 android { ... } 內部
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}