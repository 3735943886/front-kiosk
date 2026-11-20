plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kiosk.front"
    compileSdk = 34

    defaultConfig {
        applicationId = "kiosk.front"
        minSdk = 30          // 기기: Android 11
        targetSdk = 30
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // 항상 같은 키로 서명해야 adb install -r 로 업데이트가 된다.
    // CI에서는 GitHub Secret(KIOSK_KEYSTORE_B64)을 android/kiosk.keystore 로 풀어 쓴다.
    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("kiosk.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KIOSK_KEYSTORE_PASS") ?: "android"
                keyAlias = "kiosk"
                keyPassword = storePassword
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // UI는 ../../web (kiosk/web) 을 그대로 앱 assets 로 묶는다.
    sourceSets["main"].assets.srcDirs("../../web")

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
