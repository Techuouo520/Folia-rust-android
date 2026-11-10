// Compile the Rust bridge before generating and compiling its Kotlin bindings.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val ndkRevision = "29.0.14206865"
val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val sdkPath = localProperties.getProperty("sdk.dir") ?: providers.environmentVariable("ANDROID_HOME").orNull
    ?: error("Set sdk.dir in local.properties or ANDROID_HOME for this build")
val nativeDirectory = layout.projectDirectory.dir("src/main/jniLibs")
val rustLibrary = rootProject.layout.projectDirectory.file("target/aarch64-linux-android/debug/libfolia_bridge.so")
val generatedBindings = layout.buildDirectory.dir("generated/source/uniffi/kotlin")

val buildRustBridge = tasks.register<Exec>("buildRustBridge") {
    group = "build"
    description = "Build the arm64 Android Rust bridge with the pinned NDK"
    workingDir(rootProject.projectDir)
    environment("ANDROID_HOME", sdkPath)
    environment("ANDROID_NDK_HOME", File(sdkPath, "ndk/$ndkRevision").absolutePath)
    environment("CARGO_TARGET_DIR", rootProject.layout.projectDirectory.dir("target").asFile.absolutePath)
    commandLine("cargo", "ndk", "-t", "arm64-v8a", "--platform", "26", "build", "--locked", "-p", "folia-bridge", "--lib")
    outputs.file(rustLibrary)
    // Cargo tracks source and toolchain changes, including changes to git dependencies.
    outputs.upToDateWhen { false }
}

val stageRustBridge = tasks.register<Sync>("stageRustBridge") {
    dependsOn(buildRustBridge)
    // cargo-ndk 4.1.2 also tries to copy zhconv's host DLL when using its -o option.
    from(rustLibrary)
    into(nativeDirectory.dir("arm64-v8a"))
    doFirst { check(rustLibrary.asFile.isFile) { "Rust bridge artifact is missing" } }
}

abstract class GenerateUniFfiBindings : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

val generateUniFfiBindings = tasks.register<GenerateUniFfiBindings>("generateUniFfiBindings") {
    group = "build"
    description = "Generate Kotlin bindings from the freshly built Android library"
    dependsOn(stageRustBridge)
    workingDir(rootProject.projectDir)
    environment("CARGO_TARGET_DIR", rootProject.layout.projectDirectory.dir("target").asFile.absolutePath)
    commandLine("cargo", "run", "--locked", "-p", "folia-bridge", "--features", "bindgen", "--bin", "uniffi-bindgen", "--", "generate", "--library", nativeDirectory.file("arm64-v8a/libfolia_bridge.so").asFile.absolutePath, "--language", "kotlin", "--out-dir", generatedBindings.get().asFile.absolutePath, "--no-format")
    inputs.file(nativeDirectory.file("arm64-v8a/libfolia_bridge.so"))
    inputs.file(rootProject.file("crates/folia-bridge/uniffi.toml"))
    inputs.file(rootProject.file("Cargo.lock"))
    inputs.file(rootProject.file("crates/folia-bridge/src/bin/uniffi-bindgen.rs"))
    outputDirectory.set(generatedBindings)
}

android {
    namespace = "io.github.techuouo520.folia"
    compileSdk = 37
    ndkVersion = ndkRevision
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "io.github.techuouo520.folia"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateUniFfiBindings) { it.outputDirectory }
}

kotlin { jvmToolchain(17) }
tasks.named("preBuild") { dependsOn(generateUniFfiBindings) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }
}
