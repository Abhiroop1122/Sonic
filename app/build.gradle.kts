import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

/**
 * Sonic's version, set once here. Every release is tagged v<version>: "1.1.0" for a release,
 * "1.2.0-dev.1" for a development build published as a GitHub pre-release. Both are app.sonic and
 * update each other; the version code follows from the name so a development build always sorts
 * after the release before it and before its own release:
 * major × 1,000,000 + minor × 10,000 + patch × 100 + (the dev number, or 99 for a release).
 */
val sonicVersion = "1.3.0"

fun versionCodeOf(name: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-dev\.(\d+))?$""").matchEntire(name)
        ?: error("Version $name must look like 1.2.3 or 1.2.3-dev.4")
    val (major, minor, patch, dev) = m.destructured
    require(minor.toInt() < 100 && patch.toInt() < 100 && (dev.isEmpty() || dev.toInt() in 1..98)) { "Version $name is out of range" }
    return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + (dev.toIntOrNull() ?: 99)
}

android {
    namespace = "app.runout"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sonic"
        minSdk = 28
        targetSdk = 36
        versionCode = versionCodeOf(sonicVersion)
        versionName = sonicVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["runoutLabel"] = "@string/app_name"
        // The in-app update check against GitHub. F-Droid builds pass -Psonic.updates=false: F-Droid
        // brings their updates itself.
        buildConfigField("boolean", "UPDATE_CHECK", (project.findProperty("sonic.updates") as String? ?: "true").toBoolean().toString())
    }

    // No dependency metadata blob in the APK, so a build from source can match the published one
    // byte for byte (F-Droid's reproducible builds).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["runoutLabel"] = "Sonic. Dev"
        }
        release {
            isMinifyEnabled = false
            // Prototype builds are signed with the debug key so they install directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/soulseekJni"))
    // CHANGELOG.md ships inside the app: the first launch after an update shows its section.
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/changelogAssets"))
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/fixtures")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "3g"
                // -Prunout.live=true also runs the check against the real public relays.
                (project.findProperty("runout.live") as String?)?.let { v -> it.systemProperty("runout.live", v) }
                // Optional mirror for Robolectric's Android SDK jars: -Probolectric.repo=<url>
                (project.findProperty("robolectric.repo") as String?)?.let { url ->
                    it.systemProperty("robolectric.dependency.repo.url", url)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.media3:media3-exoplayer:1.9.4")
    implementation("androidx.media3:media3-session:1.9.4")
    // Internet Archive, artwork, and retained legacy protocol tests.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Liquid glass (Apache 2.0)
    implementation("io.github.kyant0:backdrop:2.0.0-alpha03")

    // The 3D turntable: Filament and its glTF loader (Apache 2.0)
    implementation("com.google.android.filament:filament-android:1.75.1")
    implementation("com.google.android.filament:gltfio-android:1.75.1")

    testImplementation(composeBom)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation("androidx.media3:media3-test-utils:1.9.4")
    testImplementation("androidx.media3:media3-test-utils-robolectric:1.9.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

room {
    schemaDirectory("$projectDir/schemas")
}

val buildSoulseekNative by tasks.registering(Exec::class) {
    description = "Build the pinned Soulseek core and JNI bridge for Android."
    group = "build"
    workingDir = rootProject.projectDir
    commandLine("bash", rootProject.file("scripts/build-native.sh").absolutePath)
    inputs.file(rootProject.file("scripts/build-native.sh"))
    inputs.files(rootProject.fileTree("native") { exclude("target/**") })
    outputs.dir(layout.buildDirectory.dir("generated/soulseekJni"))
}
tasks.named("preBuild").configure { dependsOn(buildSoulseekNative) }

val copyChangelog by tasks.registering(Copy::class) {
    description = "Bundle CHANGELOG.md for the after-update what's new."
    from(rootProject.file("CHANGELOG.md"))
    into(layout.buildDirectory.dir("generated/changelogAssets"))
}
tasks.named("preBuild").configure { dependsOn(copyChangelog) }
