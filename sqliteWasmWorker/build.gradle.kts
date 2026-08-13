@file:OptIn(ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // ES modules are what make `import.meta.url` — and therefore resolving worker.js out of the
    // npm package — available to the compiled Kotlin.
    js {
        browser()
        useEsModules()
    }
    wasmJs {
        browser()
        useEsModules()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.androidx.sqlite.web)
            implementation(npm("sqlite-wasm-worker", layout.projectDirectory.dir("worker").asFile))
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
