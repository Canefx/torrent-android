plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.btdownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.btdownloader"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        // 更新检查需要读取 BuildConfig.VERSION_CODE
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // libtorrent 的 Java/Android 绑定（需在 Android Studio 中同步验证版本号）
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-38")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
