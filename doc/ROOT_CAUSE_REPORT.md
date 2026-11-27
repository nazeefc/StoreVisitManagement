# StoreVisitManagement — Day 18 Root Cause Report

## 1. Failure Scenarios Tested & Recovery Outcomes
- **Session Expiry on 401**: When the server revokes or expires a session token, subsequent sync requests return HTTP 401 Unauthorized.
  - *Recovery Outcome*: Fixed and verified via `MainActivity` lifecycle observation and `SessionManager` StateFlow, automatically redirecting the user to Login and clearing the protected back stack.
- **Network Failure & Recovery**: Disconnecting server reachability (removing ADB reverse mapping).
  - *Recovery Outcome*: Tasks remained Pending locally; restoring server reachability allowed WorkManager to automatically synchronize pending records.
- **HTTP 500 Failure**: Triggering mock server failure simulation.
  - *Recovery Outcome*: Initial request returned HTTP 500 (triggering retry while records remained Pending); subsequent retry recovered successfully with HTTP 200.
- **Server Timeout**: Suspending Node.js mock server process.
  - *Recovery Outcome*: Requests timed out after OkHttp timeout threshold (15s) without crashing; resuming server allowed automatic synchronization.
- **Process Termination**: Force-stopping application offline.
  - *Recovery Outcome*: Pending data remained safe in Room and private storage; reconnecting synced successfully.
- **Mid-sync Interruption**: Attempted network disconnection during active synchronization.
  - *Recovery Outcome*: Partially verified; manual recovery succeeded after re-login, but exact in-flight interruption recovery was not conclusively verified.

## 2. Root Causes & Suspected Causes (Distinguished)
- **Confirmed Root Cause (Session Expiry)**: `SessionManager` managed session state imperatively without exposing reactive streams. `MainActivity` was unaware when `SyncWorker` cleared an invalid session on HTTP 401, leaving the user stranded on the protected task screen.
- **Suspected Cause (Mid-sync Interruption Delay)**: Accumulated exponential backoff delays during repeated interruption attempts, potentially causing WorkManager workers to enter prolonged backed-off states.

## 3. Impact
- Users experiencing token expiration were left on protected task screens without automatic redirection to login.
- Offline task/photo records remained safe in Room throughout all network and session failures.

## 4. Corrective Actions
- Exposed session validity via `StateFlow` (`SessionManager.isSessionValid`).
- Added lifecycle-aware session observation in `MainActivity` (`repeatOnLifecycle(Lifecycle.State.STARTED)`).
- Configured automatic navigation to `loginFragment` with protected back stack clearing (`popUpTo(R.id.nav_graph, inclusive = true)`).
- Ensured pending Room records remain intact and synchronize upon successful re-login.

## 5. Before/After Behavior
- **Before**: HTTP 401 cleared session data in background, but the UI stayed on the task list fragment.
- **After**: HTTP 401 triggers session invalidation emission, prompting `MainActivity` to immediately and cleanly navigate to Login and clear the protected back stack.

## 6. Regression Verification
- Expired active session -> Observed HTTP 401 -> Verified automatic redirection to Login.
- Re-logged in -> Verified pending data synchronized successfully.

## 7. Known Limitations
- MainActivity silently catches navigation exceptions without explicit logging.
- Physical server image cleanup is deferred.
- Valid sessions are restored using encrypted persistent storage.
