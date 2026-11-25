# StoreVisitManagement Standalone REST Mock Server

Standalone Node.js + Express + persistent SQLite mock REST server for the `StoreVisitManagement` Android application.

## Prerequisites
* Node.js v22+
* npm v10+

## Installation & Setup

1. **Install Dependencies using Lockfile**:
   ```powershell
   cd mock-server
   npm ci
   ```

2. **Initialize and Seed Database**:
   ```powershell
   npm run reset
   ```
   *(This creates `database.sqlite` with seed employees `EMP-9402` and `EMP-1001` and their assigned stores).*

3. **Start the Mock Server**:
   ```powershell
   npm start
   ```
   The server runs on `http://127.0.0.1:8080` (or `PORT` specified in environment).

4. **Run Automated Assertion Tests**:
   ```powershell
   npm test
   ```
   *The test runner spawns an isolated test server using dynamic OS-assigned ports (`PORT=0`) and temporary test database files (`tmp/test-run-*/`). The development `database.sqlite` is never modified or reset by `npm test`.*

---

## Database Locking & Safe Reset Workflow

* **Coordinated Database Lock**: `server.js` acquires an exclusive database lock (`<dbPath>.lock`) containing its PID on startup and holds it open during its lifetime.
* **Active-Server Protection & Fails-Closed Design**: `npm run reset` verifies the lockfile. If an existing lockfile is found, `reset.js` **FAILS CLOSED** and refuses reset, **even if its recorded PID appears inactive**.
* **Manual Recovery Procedure**: If a server process crashed unexpectedly and left a stale lockfile behind, manual recovery is strictly required:
  1. Confirm via Task Manager or process monitor that no related server or reset process is running.
  2. Manually remove the stale `<dbPath>.lock` file.
  3. Run `npm run reset`.
* **No Automatic Recovery**: There is no automatic stale-lock deletion. The system always fails closed for safety.

---

## Android App Connection & USB Testing (ADB Reverse)

To connect the Android app to the standalone mock server from a physical device or emulator:

1. **Start Mock Server**:
   ```powershell
   npm start
   ```
2. **Configure ADB Reverse (Physical Device / Emulator)**:
   ```powershell
   adb devices
   adb -s <device-id> reverse tcp:8080 tcp:8080
   ```
3. **Development API Base URL**:
   The Android app is configured to use `http://127.0.0.1:8080/api/v1/` for local testing via `adb reverse`.

---

## PowerShell API Manual Testing Commands

### 1. Health Check Endpoint
```powershell
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/health" -Method Get
```

### 2. Login (Synthetic Employee `EMP-9402`)
```powershell
$body = @{ employee_id = "EMP-9402"; password = "SyntheticPassword123!" } | ConvertTo-Json
$response = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/auth/login" -Method Post -ContentType "application/json" -Body $body
$token = $response.data.token
```

### 3. Fetch Assigned Stores
```powershell
$headers = @{ Authorization = "Bearer $token" }
$stores = Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/stores" -Method Get -Headers $headers
$stores.data
```

### 4. Logout
```powershell
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/v1/auth/logout" -Method Post -Headers $headers
```

---

## Driver Maintenance Limitation Note
`sqlite3` version `6.0.1` is used to resolve all `npm audit` security vulnerabilities (`0 vulnerabilities`). However, the `sqlite3` upstream library has low active maintenance. If future Node.js runtime major upgrades break ABI compatibility, migration to `better-sqlite3` or Node 22 native `node:sqlite` is recommended.
