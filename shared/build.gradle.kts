import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

// androidx.sqlite 2.7.0 ships natives/{linux_x64,linux_arm64,osx_arm64,windows_x64} but no
// osx_x64, so on an Intel Mac BundledSQLiteDriver cannot open a database at all: the first
// connection dies with "Cannot find a suitable SQLite binary for mac os x | x86_64".
//
// 2.6.2 is the last release that shipped that binary, and the JNI contract has not changed
// since — both versions declare the same native functions and their JNI_OnLoad registers them
// against the same BundledSQLite{Driver,Connection,Statement}Kt classes — so the 2.6.2 binary
// drives the 2.7.0 wrapper unchanged. Extract just that one file and put it back on the JVM
// runtime classpath under the resource path NativeLibraryLoader looks it up by, which covers
// every entry point (desktop app, jvmTest, packaged distribution) with no launch flags.
val macX64SqliteJni: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // `@jar` takes the artifact as-is; there is no variant here to select, only a file to unzip.
    add(
        macX64SqliteJni.name,
        "androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqliteMacX64Jni.get()}@jar",
    )
}

val extractMacX64SqliteJni by tasks.registering(Sync::class) {
    from(zipTree(macX64SqliteJni.elements.map { it.single().asFile })) {
        include("natives/osx_x64/**")
    }
    into(layout.buildDirectory.dir("macX64SqliteJni"))
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
            api(libs.androidx.paging.common)
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

        jvmMain {
            // Adds the osx_x64 SQLite JNI binary 2.7.0 no longer carries; see the top of the file.
            resources.srcDir(extractMacX64SqliteJni)

            dependencies {
                implementation(libs.androidx.sqlite.bundled)
            }
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
        // The database-backed suites need a real SQLite, which this variant does not have: they
        // die on "openOrCreateDatabase not mocked". The jvm suite covers the same classes against
        // the bundled driver. Matched by name rather than listed one by one, so adding a suite
        // does not quietly add a failing Android test.
        // Suites that open a database: `Database*Test` are the per-implementation ones, which
        // AndroidDatabaseTests re-declares with a Robolectric runner, and `*ComparisonTest` are
        // the ones that drive both implementations at once and have no per-platform subclass.
        exclude("**/Database*Test.class")
        exclude("**/*ComparisonTest.class")
    }
