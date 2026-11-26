package com.example.storevisitmanagement.data.sync

/**
 * HTTP and unexpected failures that should be retried by WorkManager.
 * Unexpected failures are bounded so permanent errors cannot retry forever.
 */
internal object SyncRetryPolicy {
    const val MAX_UNEXPECTED_RETRIES = 3

    fun isRetryableHttpStatus(code: Int): Boolean =
        code == 408 || code == 429 || code in 500..599

    fun shouldRetryUnexpectedly(runAttemptCount: Int): Boolean =
        runAttemptCount < MAX_UNEXPECTED_RETRIES
}
