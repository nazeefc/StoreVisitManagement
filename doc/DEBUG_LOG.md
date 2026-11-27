# StoreVisitManagement — Day 18 Debug Log

**Date**: November 27, 2026
**Branch**: `feature/day18-debug-monitor`

## Chronological Observations & Testing

1. **Network failure and recovery**
   - **Action**: Removed ADB reverse mapping while keeping wireless debugging connected. Created `Network Failure Test`.
   - **Expected**: Task saved locally with `PENDING` sync status and queued in WorkManager.
   - **Actual**: Task remained Pending in Room and UI while disconnected. Restoring ADB reverse mapping restored server reachability; WorkManager subsequently synchronized the task automatically.
   - **Result**: **PASS**.

2. **HTTP 500 failure**
   - **Action**: Triggered the mock server failure simulation endpoint (`/api/v1/debug/toggle-failure`). Created `Day18 Server Failure`.
   - **Expected**: Initial sync attempt returns HTTP 500 (triggering retry while records remain Pending), followed by WorkManager exponential backoff and successful retry.
   - **Actual**: Observed HTTP 500 followed by HTTP 200 in Network Inspector. Task automatically synchronized successfully.
   - **Result**: **PASS**.

3. **Server timeout**
   - **Action**: Suspended Node.js mock server process. Created `Day18 Timeout Test`.
   - **Expected**: Requests timeout after OkHttp/Retrofit timeout threshold (15s) without crashing app.
   - **Actual**: Network Inspector showed request durations of 15s 29ms, 15s 23ms, and 15s 18ms without HTTP response status. Task remained Pending during failure. Resumed server; task automatically synchronized.
   - **Result**: **PASS**.

4. **Expired session defect**
   - **Action**: Expired active test session in mock server database. Created `Day18 Session Expiry`.
   - **Expected**: Automatic redirection to Login upon receiving HTTP 401.
   - **Actual**: User remained on protected task screen despite session invalidation.
   - **Root Cause**: Session invalidation was not observable by `MainActivity`.
   - **Fix**: Added StateFlow-based session validity in `SessionManager`, lifecycle-aware session observation in `MainActivity`, and login navigation with protected back stack clearing.
   - **Regression Verification**: Expired a fresh session, observed HTTP 401 followed by automatic login redirection. Re-login successfully synchronized pending data.
   - **Result**: **FIXED AND VERIFIED**.

5. **Process kill and restart**
   - **Action**: Disconnected server connectivity, created `Day18 Restart Test` with a photo, force-stopped application using ADB, and reopened application.
   - **Expected**: Pending data remains available in Room and private storage.
   - **Actual**: Pending data remained available. Restored connectivity and observed successful synchronization.
   - **Result**: **PASS**.

6. **Mid-sync interruption**
   - **Action**: Verified successful baseline HTTP 200 request, suspended mock server, attempted interruption during synchronization, and disabled Wi-Fi.
   - **Expected**: Clean handling of aborted upload.
   - **Actual**: Task remained Pending. Network Inspector detached; initial health check timed out. Resumed server and verified health status OK. Task remained Pending possibly due to accumulated exponential backoff. Logout and re-login successfully completed manual recovery.
   - **Result**: **PARTIALLY VERIFIED**.

7. **Debugging Evidence**
   - Logcat showed WorkManager RETRY.
   - Background Task Inspector showed 7 retries and blocked queued workers.
   - Breakpoint inspection confirmed one pending task and one request item.
   - A suspend-all breakpoint resulted in an observed ANR during debugging; this was not established as an application defect.
   - Tools used: Logcat, Breakpoints, Network Inspector, Background Task Inspector, ADB, database inspection and PowerShell.

8. **Verification Summary**
   - Gradle build: Successful.
   - Unit tests: 10 JVM tests passed, 0 failed.
   - Git diff check: Passed.
   - Codex verdict: PASS WITH MINOR OBSERVATION.
   - Only reported Day 18 observation: silently caught navigation exceptions.
   - Outstanding verification: Actual in-flight interruption, automatic recovery after prolonged backoff, failed-state monitoring demonstration, and dashboard screenshot evidence.
