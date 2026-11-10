// Compile the Rust bridge before generating and compiling its Kotlin bindings.
import java.util.Properties
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
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

abstract class RustlsVersion : ValueSource<String, RustlsVersion.Params> {
    interface Params : ValueSourceParameters {
        val lockFile: RegularFileProperty
    }

    companion object {
        const val CRATE_NAME = "rustls-platform-verifier-android"
    }

    override fun obtain(): String {
        val lines = parameters.lockFile.get().asFile.readLines()
        val nameIdx = lines.indexOfFirst { it.trim() == "name = \"$CRATE_NAME\"" }
        val version = if (nameIdx < 0) {
            null
        } else {
            lines.drop(nameIdx + 1).firstOrNull { it.trimStart().startsWith("version = ") }?.substringAfter('"')?.substringBefore('"')?.takeIf { it.isNotEmpty() }
        }
        return version ?: error("$CRATE_NAME not found in Cargo.lock")
    }
}

val rustlsPlatformVerifierVersion = providers.of(RustlsVersion::class.java) {
    parameters.lockFile.set(rootProject.layout.projectDirectory.file("Cargo.lock"))
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if ((requested.group == "rustls" || requested.group == "org.rustls") && requested.name == "rustls-platform-verifier") {
            useVersion(rustlsPlatformVerifierVersion.get())
            because("The Kotlin verifier must match rustls-platform-verifier-android in Cargo.lock")
        }
    }
}

val llvmStrip = File(sdkPath, "ndk/$ndkRevision/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strip.exe")

abstract class StageNative : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:InputFile
    abstract val stripExecutable: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val abi = outputDirectory.get().asFile.resolve("arm64-v8a")
        abi.mkdirs()
        val library = abi.resolve("libfolia_bridge.so")
        source.get().asFile.copyTo(library, overwrite = true)
        // The cargo library stays unstripped so UniFFI can read its metadata. The packaged copy does not.
        val strip = ProcessBuilder(stripExecutable.get().asFile.absolutePath, "--strip-unneeded", library.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = strip.inputStream.bufferedReader().readText()
        val code = strip.waitFor()
        if (code != 0) {
            throw RuntimeException("llvm-strip exited $code: $output")
        }
    }
}

abstract class GenerateUniFfiBindings : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

fun releaseProperty(name: String, alternate: String): String? {
    return localProperties.getProperty(name) ?: localProperties.getProperty(alternate)
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
    val storePath = releaseProperty("RELEASE_STORE_FILE", "storeFile")
    val storePassword = releaseProperty("RELEASE_STORE_PASSWORD", "storePassword")
    val keyAliasName = releaseProperty("RELEASE_KEY_ALIAS", "keyAlias")
    val keyPasswordValue = releaseProperty("RELEASE_KEY_PASSWORD", "keyPassword")
    val releaseStore = storePath?.let { path ->
        val file = File(path)
        if (file.isAbsolute) file else rootProject.file(path)
    }
    signingConfigs {
        if (releaseStore != null && releaseStore.isFile && storePassword != null && keyAliasName != null && keyPasswordValue != null) {
            create("release") {
                storeFile = releaseStore
                this.storePassword = storePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs {
            excludes += setOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**", "lib/armeabi/**")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.onVariants { variant ->
    val release = variant.buildType == "release"
    val profile = if (release) "release" else "debug"
    val cap = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    val rustSo = rootProject.layout.projectDirectory.file("target/aarch64-linux-android/$profile/libfolia_bridge.so")
    val buildRust = tasks.register<Exec>("buildRustBridge$cap") {
        group = "build"
        description = "Build the arm64 Android Rust bridge ($profile)"
        workingDir(rootProject.projectDir)
        environment("ANDROID_HOME", sdkPath)
        environment("ANDROID_NDK_HOME", File(sdkPath, "ndk/$ndkRevision").absolutePath)
        environment("CARGO_TARGET_DIR", rootProject.layout.projectDirectory.dir("target").asFile.absolutePath)
        val command = mutableListOf("cargo", "ndk", "-t", "arm64-v8a", "--platform", "26", "build", "--locked", "-p", "folia-bridge", "--lib")
        if (release) {
            command += "--release"
        }
        commandLine(command)
        outputs.file(rustSo)
        outputs.upToDateWhen { false }
    }
    val stageRust = tasks.register<StageNative>("stageRustBridge$cap") {
        dependsOn(buildRust)
        source.set(rustSo)
        stripExecutable.set(llvmStrip)
        outputDirectory.set(layout.buildDirectory.dir("rustJni/${variant.name}"))
    }
    val bindings = layout.buildDirectory.dir("generated/source/uniffi/${variant.name}/kotlin")
    val generateBindings = tasks.register<GenerateUniFfiBindings>("generateUniFfiBindings$cap") {
        group = "build"
        description = "Generate Kotlin bindings from the $profile Rust library"
        dependsOn(buildRust)
        workingDir(rootProject.projectDir)
        environment("CARGO_TARGET_DIR", rootProject.layout.projectDirectory.dir("target").asFile.absolutePath)
        commandLine(
            "cargo", "run", "--locked", "-p", "folia-bindgen", "--",
            "generate", "--library", rustSo.asFile.absolutePath, "--language", "kotlin", "--out-dir", bindings.get().asFile.absolutePath, "--no-format",
        )
        inputs.file(rustSo)
        inputs.file(rootProject.file("crates/folia-bridge/uniffi.toml"))
        inputs.file(rootProject.file("Cargo.lock"))
        inputs.file(rootProject.file("crates/folia-bindgen/src/main.rs"))
        outputDirectory.set(bindings)
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(stageRust) { it.outputDirectory }
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateBindings) { it.outputDirectory }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.media3.session)
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.rustls.platform.verifier)
}
