plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openworldbox"
    compileSdk = 34
    // NDK 27（ubuntu-latest 预装版本）
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.openworldbox"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Native 构建配置
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17", "-fexceptions", "-frtti")
                // 只构建 64 位（与目标游戏一致）
                abiFilters += listOf("arm64-v8a")
            }
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        // 暂时不需要 databinding/viewbinding，纯自绘 UI
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Xposed API（provided，不打包进 APK）
    compileOnly("de.robv.android.xposed:api:82")

    // AndroidX 基础
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
