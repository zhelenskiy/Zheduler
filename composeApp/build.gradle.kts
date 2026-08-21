import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.metro)
}

// `org.w3c.dom` is declared twice on the Kotlin/JS path: by kotlin-dom-api-compat (which Compose
// UI and androidx.sqlite's web artifacts use) and by kotlinx-browser (also a Compose UI
// dependency). The app executable links fine, but the *test* executable reaches both copies and
// the IR linker fails with "IrClassSymbolImpl is already bound: org.w3c.dom.events/EventListener".
//
// Scoped to jsTest* on purpose: these configurations do not contribute to the app bundle, so the
// app keeps both artifacts exactly as before. kotlin-dom-api-compat is the copy that must stay —
// Compose Foundation calls its `EventListener(...)` factory, and dropping it links an
// IrLinkageError stub that throws as soon as a text field is clicked.
configurations.matching { it.name.startsWith("jsTest") }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-browser")
}

// The sounds a test adds go under `build`, not into the copy of the app the developer is running:
// the library is a real directory, and a run that fails partway would otherwise leave its files in
// it. Set here rather than in a test, because the directory is resolved at first use and any test
// class may be the one that gets there first.
tasks.withType<Test>().configureEach {
    systemProperty("zheduler.data.dir", layout.buildDirectory.dir("test-sounds").get().asFile.path)
}

kotlin {
    // A `cascadeMain` source set shared by every target cascade-editor publishes for.
    // Extending the template rather than calling dependsOn() by hand is what keeps the
    // default hierarchy — and with it webMain, iosMain, ... — in place.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("cascade") {
                withJvm()
                withIos()
                withWasmJs()
                // This module's android target comes from the AGP KMP library plugin, so
                // it is not a KotlinAndroidTarget and withAndroidTarget() misses it.
                withCompilations { it.target.platformType == KotlinPlatformType.androidJvm }
            }
        }
    }

    android {
        namespace = "com.zhelenskiy.zheduler.zheduler.composeapp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {}
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        iosTarget.binaries.all {
            linkerOpts("-lsqlite3")
        }
    }

    jvm()

    js {
        // The per-test timeout is raised in karma.config.d; see the note there.
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        // cascade-editor has no Kotlin/JS variant, so the block editor lives here and js
        // falls back to a raw Markdown field.
        getByName("cascadeMain") {
            dependencies {
                implementation(libs.cascade.editor)
            }
        }

        commonMain.dependencies {
            // Used directly throughout this source set. It happened to resolve through lifecycle
            // and flowmvi, so a bump that stopped exposing it would have broken the build here
            // for no visible reason.
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            implementation(libs.androidx.paging.compose)
            implementation(libs.reorderable)
            implementation(libs.richeditor.compose)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serializationJson)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
            implementation(projects.shared)
            implementation(libs.material.kolor)
            implementation(libs.colorpicker.compose)
            implementation(libs.kstore)
            implementation(libs.flowmvi.core)
            implementation(libs.flowmvi.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.ktor.clientCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.clientCio)
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.nucleus.system.color)
            implementation(libs.nucleus.core.runtime)
            implementation(libs.nucleus.decorated.window.jni)
            implementation(libs.nucleus.decorated.window.material3)
            implementation(libs.kstore.file)
            implementation(libs.appdirs)
        }
        androidMain.dependencies {
            implementation(libs.ktor.clientOkhttp)
            implementation(libs.kstore.file)
            implementation(libs.appdirs)
            // The background sweeper lives here rather than in androidApp: the engine has to be
            // able to re-book it after every run, and androidApp is downstream of this module.
            implementation(libs.androidx.work.runtime)
        }
        iosMain.dependencies {
            implementation(libs.ktor.clientDarwin)
            implementation(libs.kstore.file)
        }
        jsMain.dependencies {
            implementation(libs.ktor.clientJs)
            implementation(libs.kstore.storage)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.clientJs)
            implementation(libs.kstore.storage)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.zhelenskiy.zheduler.zheduler.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.zhelenskiy.zheduler.zheduler"
            packageVersion = "1.0.0"
        }
    }
}
