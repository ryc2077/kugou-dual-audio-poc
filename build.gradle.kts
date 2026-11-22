// 根构建文件：只声明插件版本（供 app/build.gradle.kts 引用）。
// 本项目没有 Gradle wrapper（没有 gradlew 二进制文件），
// GitHub Actions 用 gradle/gradle-build-action 以指定版本运行 Gradle，见 .github/workflows/android.yml。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
