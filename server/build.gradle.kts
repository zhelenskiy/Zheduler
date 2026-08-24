plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "com.zhelenskiy.zheduler.zheduler"
version = "1.0.0"
application {
    mainClass.set("com.zhelenskiy.zheduler.zheduler.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

// See :shared. The server holds the same `Outcome` discipline for its own storage layer.
kotlin { compilerOptions { freeCompilerArgs.add("-Xreturn-value-checker=check") } }

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverStatusPages)
    implementation(libs.ktor.serverCompression)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverDefaultHeaders)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverForwardedHeader)
    implementation(libs.postgresql)
    implementation(libs.hikari)

    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The end-to-end suite drives the real gateway against a real Netty server, so it needs a
    // client engine; CIO is the one that takes no native dependency.
    testImplementation(libs.ktor.clientCio)
    testImplementation(libs.ktor.clientContentNegotiation)
    testImplementation(libs.testcontainers.postgresql)
}

tasks.withType<Test>().configureEach {
    // Testcontainers prints a wall of progress to stdout; the suites that use it say what they
    // skipped themselves.
    systemProperty("testcontainers.reuse.enable", "false")
}
