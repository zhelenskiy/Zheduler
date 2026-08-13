import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
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
            implementation(libs.room3.runtime)
            api(libs.kotlinx.collections.immutable)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }

        // sqlite-bundled ships SQLite compiled from source (with the JSON1 extension the filter
        // queries need) for every non-web target. It has no js/wasmJs variant, hence per-target
        // declarations rather than a shared parent source set.
        androidMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }

        getByName("androidHostTest").dependencies {
            implementation(libs.core.ktx)
            implementation(libs.robolectric)
            implementation(libs.junit)
            // Robolectric runs on the host JVM, where the bundled driver's Android JNI cannot
            // load, so these tests go through the framework SQLite Robolectric emulates.
            implementation(libs.androidx.sqlite.framework)
        }

        iosMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }

        jvmMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }

        // webMain is automatically created by default hierarchy template
        // It's shared between jsMain and wasmJsMain
        webMain.dependencies {
            // WebWorkerSQLiteDriver plus the worker that backs it with SQLite WASM + OPFS.
            implementation(libs.androidx.sqlite.web)
            implementation(projects.sqliteWasmWorker)
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

// Room's KSP processor runs per target; there is no metadata compilation for it to hook into.
dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
    add("kspJvm", libs.room3.compiler)
    add("kspJs", libs.room3.compiler)
    add("kspWasmJs", libs.room3.compiler)
}

room3 {
    schemaDirectory(layout.projectDirectory.dir("schemas"))
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
