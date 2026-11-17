import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins { id("com.android.library") }
val coreDir = rootDir.resolve(providers.gradleProperty("rustCoreDir").orElse(projectDir.parentFile.resolve("vendor/libopenzilo").absolutePath).get())
val nativeAbis = providers.gradleProperty("nativeAbis").orElse("arm64-v8a,x86_64")
android {
    namespace = "com.openzilo.connect.core"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += nativeAbis.get().split(",") }
    }
    ndkVersion = "28.2.13676358"
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    api("net.java.dev.jna:jna:5.19.1@aar")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("net.java.dev.jna:jna:5.19.1@jar")
}
abstract class NativeBindingsTask @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val sources: DirectoryProperty
    @get:InputFiles abstract val manifests: ConfigurableFileCollection
    @get:InputFile abstract val script: RegularFileProperty
    @get:Input abstract val abis: Property<String>
    @get:Internal abstract val outputRoot: DirectoryProperty
    @get:OutputDirectory abstract val bindingsDirectory: DirectoryProperty
    @get:OutputDirectory abstract val jniDirectory: DirectoryProperty
    @TaskAction fun generate() {
        processes.exec {
            commandLine("python3", "-I", script.get().asFile.absolutePath,
                "--core", sources.get().asFile.parentFile.canonicalPath,
                "--out", outputRoot.get().asFile.canonicalPath, "--abis", abis.get())
        }
    }
}
val generateNative = tasks.register<NativeBindingsTask>("generateNative") {
    sources.set(coreDir.resolve("src"))
    manifests.from(coreDir.resolve("Cargo.toml"), coreDir.resolve("Cargo.lock"), coreDir.resolve("build.rs"), coreDir.resolve("uniffi.toml"))
    script.set(projectDir.parentFile.resolve("tools/build_native.py"))
    abis.set(nativeAbis)
    outputRoot.set(layout.buildDirectory)
    bindingsDirectory.set(layout.buildDirectory.dir("generated/uniffi"))
    jniDirectory.set(layout.buildDirectory.dir("generated/jniLibs"))
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateNative, NativeBindingsTask::bindingsDirectory)
    variant.sources.jniLibs?.addGeneratedSourceDirectory(generateNative, NativeBindingsTask::jniDirectory)
}
