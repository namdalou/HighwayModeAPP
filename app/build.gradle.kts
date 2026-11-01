import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- アプリ ID・版番号・署名（Google Play の内部テストで配布するための設定）
//  アプリ ID: Google Play は "com.example" で始まる ID を受け付けないので、自分用の ID にする。
//    ./gradlew :app:bundleRelease -Papp.id=io.github.<ユーザー名>.highwaymode
//    （GitHub Actions ではリポジトリ所有者の名前から自動で決まる。リポジトリ変数 APP_ID で変更可）
//  版番号: Play へ上げるたびに増やす必要がある（Actions ではビルド時刻から自動で決まる）
//  署名: 環境変数 UPLOAD_KEYSTORE_FILE / UPLOAD_KEYSTORE_PASSWORD / UPLOAD_KEY_ALIAS / UPLOAD_KEY_PASSWORD
//    があればその鍵（アップロード鍵）で署名する。無ければデバッグ鍵（Play には上げられない）。
fun prop(name: String): String? = (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }
fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

val appId = prop("app.id") ?: "io.github.highwaymode"
val appVersionCode = prop("app.versionCode")?.toIntOrNull() ?: 3
val uploadKeystore = (prop("upload.keystore") ?: env("UPLOAD_KEYSTORE_FILE"))?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.example.highwaymode"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "2.2.0"
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

    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = uploadKeystore
                storePassword = prop("upload.storePassword") ?: env("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = prop("upload.keyAlias") ?: env("UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = prop("upload.keyPassword") ?: env("UPLOAD_KEY_PASSWORD")
                    ?: prop("upload.storePassword") ?: env("UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // アップロード鍵があればそれで署名（Google Play 用）。無ければデバッグ鍵（APK を直接入れる個人利用向け）
            signingConfig = if (uploadKeystore != null) signingConfigs.getByName("upload") else signingConfigs.getByName("debug")
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
    // Spectrum: パソコンなしで DUMP 権限(音声の番号の読み取り用)を許可するため
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
