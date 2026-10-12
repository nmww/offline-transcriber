plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nightowl.transcriber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nightowl.transcriber"
        minSdk = 24
        targetSdk = 35
        // CI 打包时通过 -PverCode / -PverName 注入；本地默认 1.0
        versionCode = (project.findProperty("verCode")?.toString()?.toIntOrNull()) ?: 1
        versionName = project.findProperty("verName")?.toString() ?: "1.0"

        ndk {
            // sherpa-onnx 原生库按规格书仅发 arm64-v8a（2018 年后主流机型全覆盖）
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 签名配置：仅 CI 传入 -PksFile 等参数时生效；本地不传则产出 unsigned APK
    signingConfigs {
        create("release") {
            val ks = project.findProperty("ksFile")?.toString()
            if (ks != null) {
                storeFile = rootProject.file(ks)
                storePassword = project.findProperty("ksPass")?.toString()
                keyAlias = project.findProperty("ksAlias")?.toString()
                keyPassword = project.findProperty("ksPass2")?.toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (project.hasProperty("ksFile")) {
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
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // ---- Compose ----
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- 适配/适配-specific ----
    implementation("androidx.compose.material3:material3-window-size-class:1.3.1")

    // ---- 存储 / 文件 ----
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ---- Room（转写记录持久化）----
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ---- DataStore（设置项）----
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ---- sherpa-onnx 引擎 ----
    // 官方 Kotlin API 源码已内置（java/com/k2fsa/sherpa/onnx/*.kt），
    // 预编译原生库已内置（jniLibs/arm64-v8a/*.so，来自官方 v1.10.30 release）。
    // 因此【不需要】任何 maven 坐标依赖；勿添加 com.k2fsa.sherpa.onnx（Maven Central 上并不存在）。
}
