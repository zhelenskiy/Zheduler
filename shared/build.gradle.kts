import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    android {
        namespace = "com.zhelenskiy.zheduler.zheduler.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        androidResources {
            enable = true
        }

        withHostTest {
            isIncludeAndroidResources = true
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
            api(libs.kotlinx.collections.immutable)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.sqldelight.android)
            implementation(libs.sqlite.requery)  // SQLite with JSON1 extension support
        }

        getByName("androidHostTest").dependencies {
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
            implementation(npm("sql.js", "1.14.1"))
            implementation(npm("@cashapp/sqldelight-sqljs-worker", libs.versions.sqldelight.get()))
            implementation(devNpm("copy-webpack-plugin", libs.versions.webPackPlugin.get()))
            implementation(npm("@js-joda/timezone", "2.25.2"))
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

tasks.withType<Test>().matching { it.name.contains("AndroidHostTest", ignoreCase = true) }
    .configureEach {
        systemProperty("robolectric.logging", "stdout")
        exclude("**/DatabaseTaskRepositoryTest.class")
        exclude("**/DatabaseTaskAdvancedRepositoryTest.class")
        exclude("**/DatabaseTaskAutomationRepositoryTest.class")
        exclude("**/DatabaseTaskFiltersRepositoryTest.class")
        exclude("**/DatabaseRecurrenceRepositoryTest.class")
        exclude("**/DatabaseRecurrenceEdgeCasesRepositoryTest.class")
        exclude("**/DatabaseConcurrencyRepositoryTest.class")
        exclude("**/DatabaseCalculateStatusFromSubtasksRepositoryTest.class")
        exclude("**/DatabaseIsMissedRepositoryTest.class")
        exclude("**/DatabaseSearchTasksForConnectionTest.class")
        exclude("**/DatabaseSavedFilterRepositoryTest.class")
        exclude("**/GroupedTaskQueriesComparisonTest.class")
    }
