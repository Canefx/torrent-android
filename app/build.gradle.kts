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
        versionCode = 5
        versionName = "0.4.0"
    }

    // 按 ABI 拆包：每个包只带一份原生库，体积大约减半
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packaging {
        // 压缩 APK 内部的 .so（libtorrent 原生库占绝大部分体积）
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "DebugProbesKt.bin",
                "kotlin/**",
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // 更新检查需要读取 BuildConfig.VERSION_CODE
        buildConfig = true
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
    // libtorrent 的 Java/Android 绑定
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-38")
    // 仅用于 FileProvider（安装更新包需要）
    implementation("androidx.core:core-ktx:1.13.1")
}
