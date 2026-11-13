import com.android.build.api.variant.FilterConfiguration.FilterType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing comes from keystore.properties (not committed); without it release stays unsigned,
// which does not affect debug builds or CI AAB packaging
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.phoneinteraction.free"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.phoneinteraction.free"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.9.0"
    }

    // Ship one APK per real-device ABI instead of a universal APK: the release APK carries
    // MediaPipe + sherpa-onnx native libraries for four ABIs, and the two x86 variants are
    // useless on phones while roughly doubling the download size.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                // Missing key fails fast with a readable error instead of a hard-to-locate NPE
                fun required(key: String): String = keystoreProps.getProperty(key)
                    ?: error("keystore.properties is missing required key: $key")
                // keystore.properties lives at the project root; storeFile resolves relative to it
                storeFile = rootProject.file(required("storeFile"))
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Store builds enable R8 + resource shrinking; keep rules in proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// Assign distinct versionCode per ABI split so each APK can be independently
// installed/updated without conflicts. base=2 -> arm64-v8a=1002, armeabi-v7a=2002.
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters
                .find { it.filterType == FilterType.ABI }
                ?.identifier
            val abiCode = mapOf("arm64-v8a" to 1, "armeabi-v7a" to 2)[abiName]
            if (abiCode != null) {
                output.versionCode.set(abiCode * 1000 + 2)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Export Room schema JSON to app/schemas so schema and migrations are verifiable at build time
// and MigrationTestHelper migration tests get a baseline
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sherpa.onnx)

    testImplementation(libs.junit)
}
