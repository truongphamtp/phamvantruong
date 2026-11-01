plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.truongphamtp.docsach"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.truongphamtp.docsach"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Khoá ký dùng chung cho bản cài thử, để cài bản mới đè lên bản cũ mà không mất dữ liệu.
        // Nếu phát hành lên Google Play, hãy tạo khoá riêng và giữ bí mật.
        create("shared") {
            storeFile = file("doc-sach-dev.keystore")
            storePassword = "docsach"
            keyAlias = "docsach"
            keyPassword = "docsach"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // Ảnh chụp giao diện (Robolectric) chỉ chạy khi gọi với -Pscreenshots=true.
                val screenshots = (project.findProperty("screenshots") ?: "false").toString()
                test.systemProperty("docsach.screenshots", screenshots)
                if (screenshots != "true") test.filter.excludeTestsMatching("*ScreenshotTest")
                test.maxHeapSize = "3g"
            }
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.window:window:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jsoup:jsoup:1.18.3")
    // Hiệu ứng kính mờ (Liquid Glass) cho thanh công cụ và bảng nổi.
    implementation("dev.chrisbanes.haze:haze:1.1.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
