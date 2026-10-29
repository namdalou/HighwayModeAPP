import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// PC・GitHub Actions 用の地図データ作成ツール（HWDB の作成・目録作成・検証）
//   ./gradlew :datatool:run --args="help"
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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

application {
    mainClass.set("com.example.highwaymode.datatool.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g", "-Dfile.encoding=UTF-8")
}

dependencies {
    implementation(project(":core"))
}

tasks.named<JavaExec>("run") {
    // 相対パスは呼び出したディレクトリ基準にする
    workingDir = rootProject.projectDir
}
