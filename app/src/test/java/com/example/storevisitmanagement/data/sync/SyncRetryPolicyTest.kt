package com.example.storevisitmanagement.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRetryPolicyTest {
    @Test
    fun retryableHttpStatusesIncludeTimeoutRateLimitAndServerErrors() {
        listOf(408, 429, 500, 503, 599).forEach {
            assertTrue("$it should be retried", SyncRetryPolicy.isRetryableHttpStatus(it))
        }
    }

    @Test
    fun clientValidationAndSuccessStatusesAreNotRetried() {
        listOf(200, 400, 401, 403, 404, 409, 422).forEach {
            assertFalse("$it should not be retried", SyncRetryPolicy.isRetryableHttpStatus(it))
        }
    }

    @Test
    fun unexpectedFailuresHaveABoundedRetryWindow() {
        assertTrue(SyncRetryPolicy.shouldRetryUnexpectedly(0))
        assertTrue(SyncRetryPolicy.shouldRetryUnexpectedly(2))
        assertFalse(SyncRetryPolicy.shouldRetryUnexpectedly(SyncRetryPolicy.MAX_UNEXPECTED_RETRIES))
    }
}
