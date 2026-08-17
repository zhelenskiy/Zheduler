rootProject.name = "zheduler"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // Narrowed to the one library that needs it. JitPack builds arbitrary repositories on
        // demand, so an unfiltered entry will happily answer for any coordinate the repositories
        // above do not have — a typo, or a group someone else has taken.
        maven("https://jitpack.io") {
            mavenContent { includeGroupAndSubgroups("io.github.linreal") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":composeApp")
include(":server")
include(":shared")
include(":sqliteWasmWorker")