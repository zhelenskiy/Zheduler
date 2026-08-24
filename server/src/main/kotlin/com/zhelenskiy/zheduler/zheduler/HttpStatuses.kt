package com.zhelenskiy.zheduler.zheduler

import io.ktor.http.HttpStatusCode

/**
 * Two statuses named here rather than taken from [HttpStatusCode].
 *
 * 428 has no constant at all, and 413's has been renamed once already; spelling both out keeps a
 * Ktor upgrade from turning a deliberate status into a deprecation warning or a compile error.
 */
internal val PreconditionRequiredStatus = HttpStatusCode(428, "Precondition Required")

internal val ContentTooLargeStatus = HttpStatusCode(413, "Content Too Large")
