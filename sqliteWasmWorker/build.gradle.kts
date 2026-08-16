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
            // Supplies the org.w3c.dom externals — Worker among them — that Kotlin/Wasm has no
            // stdlib copy of. Kotlin/JS gets the same declarations from kotlin-dom-api-compat.
            implementation(libs.kotlinx.browser)
        }
    }
}
