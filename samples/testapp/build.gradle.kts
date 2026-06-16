@file:OptIn(ExperimentalWasmDsl::class)

import java.util.Properties
import org.gradle.kotlin.dsl.implementation
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.ksp)
    alias(libs.plugins.skie)
}

val projectVersionCode: Int by rootProject.extra
val projectVersionName: String by rootProject.extra

val disableWebTargets = project.properties["disable.web.targets"]?.toString()?.toBoolean() ?: false

// If changing it here, it must also be changed in XCode "Signing and Capabilities", under
// "Associated Domains"
val applinkHost = "apps.multipaz.org"

// Hedera testnet operator for the x402 settlement demo. Read from a gitignored
// local.properties (preferred) or the environment; absent ⇒ empty ⇒ the demo's
// settlement path is simply disabled (never breaks the build).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String =
    localProps.getProperty(name) ?: System.getenv(name) ?: ""

buildConfig {
    packageName("org.multipaz.testapp")
    buildConfigField("TEST_APP_UPDATE_URL", System.getenv("TEST_APP_UPDATE_URL") ?: "")
    buildConfigField("TEST_APP_UPDATE_WEBSITE_URL", System.getenv("TEST_APP_UPDATE_WEBSITE_URL") ?: "")
    buildConfigField("APPLINK_HOST", applinkHost)
    buildConfigField("HEDERA_OPERATOR_ID", secret("HEDERA_OPERATOR_ID"))
    buildConfigField("HEDERA_OPERATOR_KEY", secret("HEDERA_OPERATOR_KEY"))
    useKotlinOutput { internalVisibility = false }
}

kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors = true
        }
    }

    if (!disableWebTargets) {
        wasmJs {
            browser {
            }
            binaries.executable()
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            freeCompilerArgs += listOf(
                // This is how we specify the minimum iOS version as 26.0
                "-Xoverride-konan-properties=" +
                        "osVersionMin.ios_arm64=26.0;" +
                        "osVersionMin.ios_simulator_arm64=26.0;" +
                        "osVersionMin.ios_x64=26.0",
                // Uncomment the following to get Garbage Collection logging when using the framework:
                //
                // "-Xruntime-logs=gc=info"
            )
            linkerOpts("-lsqlite3")
            baseName = "Multipaz"
            isStatic = true
            export(project(":multipaz"))
            export(project(":multipaz-doctypes"))
            export(project(":multipaz-utopia"))
            export(project(":multipaz-longfellow"))
            export(project(":multipaz-dcapi"))
            export(project(":multipaz-swiftui"))
            export(libs.ktor.client.darwin)
            export(libs.kotlinx.io.bytestring)
            export(libs.kotlinx.datetime)
            export(libs.kotlinx.coroutines.core)
            export(libs.kotlinx.serialization.json)
            export(libs.ktor.client.core)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val iosMain by getting {
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.androidx.sqlite)
                implementation(libs.androidx.sqlite.framework)

                api(project(":multipaz"))
                api(project(":multipaz-doctypes"))
                api(project(":multipaz-utopia"))
                api(project(":multipaz-longfellow"))
                api(project(":multipaz-dcapi"))
                api(project(":multipaz-swiftui"))
                api(libs.ktor.client.darwin)
                api(libs.kotlinx.io.bytestring)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                api(libs.ktor.client.core)
            }
        }

        val iosX64Main by getting {
            dependencies {}
        }

        val iosArm64Main by getting {
            dependencies {}
        }

        val iosSimulatorArm64Main by getting {
            dependencies {}
        }

        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation(libs.androidx.activity.compose)
                implementation(libs.bouncy.castle.bcprov)
                implementation(libs.androidx.biometrics)
                implementation(libs.ktor.client.android)
                implementation(libs.process.phoenix)
                implementation(libs.accompanist.drawablepainter)
                // Native Hedera SDK for the x402 Hedera payment-method demo
                // (Android/JVM only; the credential seam lives in androidMain).
                implementation(libs.hedera.sdk)
                // The Hedera SDK needs a gRPC transport provider at runtime to reach the
                // network; supply grpc-okhttp explicitly (works on Android and the JVM host).
                // Version matches the SDK's grpc-api (1.72.0).
                implementation("io.grpc:grpc-okhttp:1.72.0")
                // QR encoding for the one-time recovery-key screen.
                implementation(libs.zxing.core)
            }
        }

        val androidUnitTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // grpc-okhttp transport comes from androidMain (needed for the live-settle test).
            }
        }

        val androidInstrumentedTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.androidx.test.junit)
                implementation("androidx.test:runner:1.6.2")
            }
        }

        if (!disableWebTargets) {
            val wasmJsMain by getting {
                dependencies {
                    implementation(libs.ktor.client.js)
                }
            }
        }

        val commonMain by getting {
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.components.uiToolingPreview)
                implementation(compose.materialIconsExtended)
                implementation(libs.jetbrains.navigationevent.compose)
                implementation(libs.jetbrains.navigation.compose)
                implementation(libs.jetbrains.navigation.runtime)
                implementation(libs.jetbrains.lifecycle.viewmodel.compose)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.network)
                implementation(libs.semver)

                implementation(project(":multipaz"))
                implementation(project(":multipaz-compose"))
                implementation(project(":multipaz-dcapi"))
                implementation(project(":multipaz-doctypes"))
                implementation(project(":multipaz-utopia"))
                implementation(project(":multipaz-longfellow"))
                implementation(project(":multipaz-swiftui"))
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.io.core)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.coil.compose)
                implementation(libs.coil.ktor3)
            }
        }
    }
}

android {
    namespace = "org.multipaz.testapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    sourceSets["main"].manifest.srcFile("src/androidMain/AndroidManifest.xml")
    sourceSets["main"].res.srcDirs("src/androidMain/res")

    defaultConfig {
        applicationId = "org.multipaz.testapp"
        manifestPlaceholders["applinkHost"] = applinkHost
        minSdk = 29
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = projectVersionCode
        versionName = projectVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += listOf("/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
            // The Hedera SDK pulls in grpc/netty/protobuf, which ship duplicate
            // META-INF entries that otherwise fail the merge.
            excludes += listOf(
                "/META-INF/INDEX.LIST",
                "/META-INF/DEPENDENCIES",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/native-image/**",
            )
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    flavorDimensions.addAll(listOf("standard"))
    productFlavors {
        create("blue") {
            dimension = "standard"
            isDefault = true
        }
        create("red") {
            dimension = "standard"
            applicationId = "org.multipaz.testapp.red"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    dependencies {
        debugImplementation(compose.uiTooling)
    }
}

dependencies {
    add("kspCommonMainMetadata", project(":multipaz-cbor-rpc"))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().all {
    if (name != "kspCommonMainKotlinMetadata") {
        dependsOn("kspCommonMainKotlinMetadata")
    }
}

tasks["compileKotlinIosX64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosArm64"].dependsOn("kspCommonMainKotlinMetadata")
tasks["compileKotlinIosSimulatorArm64"].dependsOn("kspCommonMainKotlinMetadata")
if (!disableWebTargets) {
    tasks["compileKotlinWasmJs"].dependsOn("kspCommonMainKotlinMetadata")
}
