plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.mockgo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.mockgo"
        minSdk = 24
        targetSdk = 32
        versionCode = 3
        versionName = "1.2.0"
    }

    // 固定签名：把 mockgo.keystore 放在 app/ 目录下，构建出的 APK 签名每次都一样
    val ks = file("mockgo.keystore")
    if (ks.exists()) {
        signingConfigs {
            create("fixed") {
                storeFile = ks
                storePassword = "mockgo123"
                keyAlias = "mockgo"
                keyPassword = "mockgo123"
            }
        }
    }

    buildTypes {
        debug {
            if (ks.exists()) signingConfig = signingConfigs.getByName("fixed")
        }
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
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 地图：高德 3D 地图 SDK（GCJ-02 坐标，在线加载，不需要离线地图）
    implementation("com.amap.api:3dmap:9.8.3")
}
