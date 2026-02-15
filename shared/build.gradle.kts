import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    // Apply default hierarchy first, then add custom source sets
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serializationJson)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.sqldelight.runtime)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android)
        }

        androidUnitTest.dependencies {
            implementation(libs.core.ktx)
            implementation(libs.robolectric)
            implementation(libs.junit)
        }

        iosMain.dependencies {
            implementation(libs.sqldelight.native)
        }

        jvmMain.dependencies {
            implementation(libs.sqldelight.jvm)
        }

        // webMain is automatically created by default hierarchy template
        // It's shared between jsMain and wasmJsMain
        webMain.dependencies {
            implementation(libs.sqldelight.web)  // web-worker-driver used by both JS and WasmJS
            implementation(npm("sql.js", "1.12.0"))
            implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
            implementation(devNpm("copy-webpack-plugin", libs.versions.webPackPlugin.get()))
            implementation(npm("@js-joda/timezone", "2.18.3"))
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

sqldelight {
    databases {
        create("ZhedulerDatabase") {
            packageName.set("com.zhelenskiy.zheduler.zheduler.db")
            generateAsync = true
        }
    }
    linkSqlite = true
}

android {
    namespace = "com.zhelenskiy.zheduler.zheduler.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("robolectric.logging", "stdout")
                // Exclude commonTest Database*Test classes since we have Android-specific versions with Robolectric
                it.exclude("**/DatabaseTaskRepositoryTest.class")
                it.exclude("**/DatabaseTaskAdvancedRepositoryTest.class")
                it.exclude("**/DatabaseTaskAutomationRepositoryTest.class")
                it.exclude("**/DatabaseTaskFiltersRepositoryTest.class")
                it.exclude("**/DatabaseRecurrenceRepositoryTest.class")
                it.exclude("**/DatabaseRecurrenceEdgeCasesRepositoryTest.class")
                it.exclude("**/DatabaseConcurrencyRepositoryTest.class")
                it.exclude("**/DatabaseCalculateStatusFromSubtasksRepositoryTest.class")
                it.exclude("**/DatabaseIsMissedRepositoryTest.class")
            }
        }
    }
}
