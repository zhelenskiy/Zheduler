import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
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
            api(libs.room3.runtime)
            api(libs.kotlinx.collections.immutable)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.sqlite.bundled)
        }

        androidUnitTest.dependencies {
            implementation(libs.core.ktx)
            implementation(libs.robolectric)
            implementation(libs.junit)
            implementation(libs.sqlite.bundled)
        }

        iosMain.dependencies {
            implementation(libs.sqlite.bundled)
        }

        jvmMain.dependencies {
            implementation(libs.sqlite.bundled)
        }

        jvmTest.dependencies {
            implementation(libs.sqlite.bundled)
        }

        // webMain is automatically created by default hierarchy template
        // It's shared between jsMain and wasmJsMain
        webMain.dependencies {
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.web)
        }

        webTest.dependencies {
            implementation(libs.sqlite.web)
        }

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.room3.runtime)
            implementation(libs.sqlite.web)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
    add("kspJvm", libs.room3.compiler)
    add("kspJs", libs.room3.compiler)
    add("kspWasmJs", libs.room3.compiler)
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
                it.exclude("**/DatabaseSearchTasksForConnectionTest.class")
                it.exclude("**/DatabaseSavedFilterRepositoryTest.class")
                it.exclude("**/GroupedTaskQueriesComparisonTest.class")
            }
        }
    }
}
