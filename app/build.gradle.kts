import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.highwaymode"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.highwaymode"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
        // 地図データ（HWDB）の配布目録。GitHub Actions では自動で設定される:
        //   ./gradlew :app:assembleRelease -Phwdb.manifestUrl=https://github.com/<user>/<repo>/releases/download/hwdb-latest/hwdb-manifest.json
        val manifestUrl = (project.findProperty("hwdb.manifestUrl") as String?) ?: ""
        buildConfigField("String", "HWDB_MANIFEST_URL", "\"" + manifestUrl.replace("\"", "") + "\"")
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // 地図データは自前で圧縮済み。無圧縮で格納すると APK から直接（コピーせずに）読める
        noCompress += "hwdb"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 個人利用向け: デバッグ鍵で署名（ストア公開時は自分の鍵に変更）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // Android Auto（Car App Library）。app-projected は Android Auto で車両の情報（車速など）を取るのに必要
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.core:core:1.13.1")
}
