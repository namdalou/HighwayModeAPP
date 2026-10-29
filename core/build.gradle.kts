import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android に依存しない演算モジュール（方位推定・マップマッチング・到着予測・OSM 解析）
// JVM 上で単体テスト・走行シミュレーションを実行できる: ./gradlew :core:test
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // シミュレーション結果（精度レポート）をコンソールに出す
    testLogging {
        showStandardStreams = true
        events("passed", "failed")
    }
}
