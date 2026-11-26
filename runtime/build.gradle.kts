plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace="app.filmmerkezi.addon.runtime";compileSdk=37
    defaultConfig{minSdk=23}
    compileOptions{sourceCompatibility=JavaVersion.VERSION_11;targetCompatibility=JavaVersion.VERSION_11}
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) } }
val hostClasses=project(":native").layout.buildDirectory.file("intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar")
dependencies {
    compileOnly(files(hostClasses));compileOnly(project(":pluginApi"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    compileOnly("com.squareup.okhttp3:okhttp:5.5.1");compileOnly("org.jsoup:jsoup:1.23.2")
    compileOnly("org.jspecify:jspecify:1.0.0")
}
tasks.matching{it.name.startsWith("compile")&&it.name.endsWith("Kotlin")}.configureEach{dependsOn(":native:bundleDebugClassesToCompileJar")}
