plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.openzilo.input"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.openzilo.input"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    androidResources { generateLocaleConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs.useLegacyPackaging = false }
    bundle { language.enableSplit = false }
}
val speechRuntime = layout.buildDirectory.file("speech/sherpa-onnx-2.0.1.aar")
val fetchSpeechRuntime = tasks.register<Exec>("fetchSpeechRuntime") {
    inputs.file(rootProject.file("tools/fetch_speech.py"))
    outputs.file(speechRuntime)
    commandLine("python3", "-I", rootProject.file("tools/fetch_speech.py"), speechRuntime.get().asFile)
}
dependencies {
    implementation(files(speechRuntime).builtBy(fetchSpeechRuntime))
    implementation(project(":connect-api"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.11.0")
    implementation("androidx.savedstate:savedstate:1.4.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
