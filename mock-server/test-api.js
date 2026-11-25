const http = require('http');
const assert = require('assert');
const sqlite3 = require('sqlite3').verbose();
const path = require('path');
const fs = require('fs');
const { spawn, execSync } = require('child_process');

const mockServerDir = fs.realpathSync(__dirname);
const tmpBaseDir = path.join(mockServerDir, 'tmp');
const runId = `test-run-${Date.now()}-${Math.floor(Math.random() * 10000)}`;
const testTmpDir = path.join(tmpBaseDir, runId);

const TEST_HOST = '127.0.0.1';
const testDbPath = path.join(testTmpDir, 'test-database.sqlite');
const testUploadsDir = path.join(testTmpDir, 'test-uploads');

function request(port, options, body = null) {
    return new Promise((resolve, reject) => {
        const defaultOptions = {
            hostname: TEST_HOST,
            port: port,
            ...options
        };
        const req = http.request(defaultOptions, (res) => {
            let data = '';
            res.on('data', chunk => data += chunk);
            res.on('end', () => {
                try {
                    const parsed = JSON.parse(data);
                    resolve({ status: res.statusCode, body: parsed });
                } catch {
                    resolve({ status: res.statusCode, body: data });
                }
            });
        });
        req.on('error', err => reject(err));
        if (body) {
            req.write(typeof body === 'string' ? body : JSON.stringify(body));
        }
        req.end();
    });
}

function startServer(env) {
    return new Promise((resolve, reject) => {
        const serverProcess = spawn('node', ['server.js'], {
            cwd: mockServerDir,
            env: { ...process.env, ...env },
            stdio: ['ignore', 'pipe', 'pipe', 'ipc']
        });

        let hasResolved = false;
        let stderrOutput = '';

        const onData = (data) => {
            const msg = data.toString();
            const match = msg.match(/running on http:\/\/127\.0\.0\.1:(\d+)/);
            if (match && match[1]) {
                if (!hasResolved) {
                    hasResolved = true;
                    const assignedPort = parseInt(match[1], 10);
                    resolve({ process: serverProcess, port: assignedPort });
                }
            }
        };

        serverProcess.stdout.on('data', onData);
        serverProcess.stderr.on('data', (data) => {
            stderrOutput += data.toString();
        });

        serverProcess.on('error', (err) => {
            if (!hasResolved) {
                hasResolved = true;
                stopServer(serverProcess).then(() => {
                    reject(new Error(`Server error event: ${err.message}. Stderr: ${stderrOutput}`));
                }).catch((stopErr) => {
                    reject(new Error(`Server error event: ${err.message} AND stopServer failed: ${stopErr.message}. Stderr: ${stderrOutput}`));
                });
            }
        });

        serverProcess.on('exit', (code) => {
            if (!hasResolved) {
                hasResolved = true;
                stopServer(serverProcess).then(() => {
                    reject(new Error(`Server exited prematurely with code ${code}. Stderr: ${stderrOutput}`));
                }).catch((stopErr) => {
                    reject(new Error(`Server exited prematurely with code ${code} AND stopServer failed: ${stopErr.message}. Stderr: ${stderrOutput}`));
                });
            }
        });

        setTimeout(() => {
            if (!hasResolved) {
                hasResolved = true;
                stopServer(serverProcess).then(() => {
                    reject(new Error(`Server startup timed out after 5000ms. Stderr: ${stderrOutput}`));
                }).catch((stopErr) => {
                    reject(new Error(`Server startup timed out after 5000ms AND stopServer failed: ${stopErr.message}. Stderr: ${stderrOutput}`));
                });
            }
        }, 5000);
    });
}

function stopServer(serverProcess, lockPathToCheck = null) {
    return new Promise((resolve, reject) => {
        if (!serverProcess || (serverProcess.exitCode !== null && serverProcess.exitCode !== undefined)) {
            if (lockPathToCheck && fs.existsSync(lockPathToCheck)) {
                return reject(new Error(`LOCK LEAK ERROR: Lockfile '${lockPathToCheck}' was not released upon server shutdown.`));
            }
            return resolve();
        }

        const pid = serverProcess.pid;
        let isDone = false;

        const handleExit = () => {
            if (!isDone) {
                isDone = true;
                if (lockPathToCheck && fs.existsSync(lockPathToCheck)) {
                    return reject(new Error(`LOCK LEAK ERROR: Lockfile '${lockPathToCheck}' was not released upon server shutdown (PID ${pid}).`));
                }
                resolve();
            }
        };

        serverProcess.once('exit', handleExit);
        serverProcess.once('close', handleExit);

        // 1. Try graceful IPC shutdown first
        try {
            serverProcess.send('SHUTDOWN');
        } catch {
            try { serverProcess.kill('SIGTERM'); } catch {}
        }

        // 2. Fallback to forced termination if graceful shutdown takes longer than 2000ms
        const fallbackTimer = setTimeout(() => {
            if (!isDone) {
                if (process.platform === 'win32') {
                    try {
                        execSync(`taskkill /F /PID ${pid} /T`, { stdio: 'ignore' });
                    } catch {}
                } else {
                    try { serverProcess.kill('SIGKILL'); } catch {}
                }
            }
        }, 2000);

        setTimeout(() => {
            clearTimeout(fallbackTimer);
            if (!isDone) {
                if (serverProcess.exitCode !== null && serverProcess.exitCode !== undefined) {
                    isDone = true;
                    if (lockPathToCheck && fs.existsSync(lockPathToCheck)) {
                        return reject(new Error(`LOCK LEAK ERROR: Lockfile '${lockPathToCheck}' was not released upon server shutdown.`));
                    }
                    resolve();
                } else {
                    reject(new Error(`FAILED TO CONFIRM PROCESS TERMINATION: Process PID ${pid} did not exit within 3000ms.`));
                }
            }
        }, 3000);
    });
}

function sanitizeToken(token) {
    if (!token || typeof token !== 'string') return 'N/A';
    return token.substring(0, 8) + '...';
}

async function runSuite() {
    console.log('=== RUNNING MOCK REST SERVER AUTOMATED TEST SUITE (FINAL TEST 16 CORRECTION) ===\n');

    let activeServer = null;
    let preserveTempOnFailure = false;
    let passedCount = 0;
    let failedCount = 0;
    let skippedCount = 0;

    try {
        // Create isolated test environment
        fs.mkdirSync(testTmpDir, { recursive: true });
        fs.mkdirSync(testUploadsDir, { recursive: true });

        const env = {
            PORT: '0', // Dynamic OS-assigned port
            HOST: TEST_HOST,
            DB_PATH: testDbPath,
            UPLOADS_DIR: testUploadsDir
        };

        // Initialize & seed isolated test database
        execSync('node reset.js', { cwd: mockServerDir, env, stdio: 'ignore' });

        activeServer = await startServer(env);
        let currentPort = activeServer.port;
        console.log(`Dynamic test server spawned on port ${currentPort}`);

        const lockPath = testDbPath + '.lock';

        // --- SECTION 1: 12 EXISTING API ASSERTIONS ---

        // Test 1: Health Endpoint
        const healthRes = await request(currentPort, { path: '/api/v1/health', method: 'GET' });
        assert.strictEqual(healthRes.status, 200, 'Health endpoint status must be 200');
        assert.strictEqual(healthRes.body.status, 'ok', 'Health response status must be ok');
        assert.ok(typeof healthRes.body.timestamp === 'number', 'Health response must contain numeric timestamp');
        passedCount++;
        console.log('PASS [Test 1]: GET /api/v1/health responded HTTP 200 OK');

        // Test 2: Login Success (EMP-9402)
        const loginRes = await request(
            currentPort,
            { path: '/api/v1/auth/login', method: 'POST', headers: { 'Content-Type': 'application/json' } },
            { employee_id: 'EMP-9402', password: 'SyntheticPassword123!' }
        );
        assert.strictEqual(loginRes.status, 200, 'Valid login must return 200 OK');
        assert.strictEqual(loginRes.body.status, 'success', 'Login status must be success');
        assert.ok(loginRes.body.data.token, 'Login must return session token');
        assert.ok(loginRes.body.data.expires_at > Date.now(), 'Token expires_at must be in the future');
        const token1 = loginRes.body.data.token;
        passedCount++;
        console.log(`PASS [Test 2]: POST /api/v1/auth/login (EMP-9402) succeeded (Token: ${sanitizeToken(token1)})`);

        // Test 3: Login Bad Password
        const badLoginRes = await request(
            currentPort,
            { path: '/api/v1/auth/login', method: 'POST', headers: { 'Content-Type': 'application/json' } },
            { employee_id: 'EMP-9402', password: 'WrongPassword!' }
        );
        assert.strictEqual(badLoginRes.status, 401, 'Invalid password must return HTTP 401');
        assert.strictEqual(badLoginRes.body.status, 'error', 'Error status must be error');
        passedCount++;
        console.log('PASS [Test 3]: POST /api/v1/auth/login with bad password returned HTTP 401');

        // Test 4: Get Stores for EMP-9402
        const stores1Res = await request(currentPort, {
            path: '/api/v1/stores', method: 'GET',
            headers: { 'Authorization': `Bearer ${token1}` }
        });
        assert.strictEqual(stores1Res.status, 200, 'Stores request with valid token must return 200 OK');
        assert.strictEqual(stores1Res.body.data.length, 3, 'EMP-9402 must have exactly 3 assigned stores');
        assert.deepStrictEqual(
            stores1Res.body.data.map(s => s.id).sort(),
            ['STR-001', 'STR-002', 'STR-003'].sort(),
            'EMP-9402 stores must match assigned seed IDs'
        );
        passedCount++;
        console.log('PASS [Test 4]: GET /api/v1/stores returned 3 assigned stores for EMP-9402');

        // Test 5: Authorization Verification (EMP-1001)
        const login2Res = await request(
            currentPort,
            { path: '/api/v1/auth/login', method: 'POST', headers: { 'Content-Type': 'application/json' } },
            { employee_id: 'EMP-1001', password: 'OtherPassword123!' }
        );
        assert.strictEqual(login2Res.status, 200);
        const token2 = login2Res.body.data.token;

        const stores2Res = await request(currentPort, {
            path: '/api/v1/stores', method: 'GET',
            headers: { 'Authorization': `Bearer ${token2}` }
        });
        assert.strictEqual(stores2Res.status, 200);
        assert.strictEqual(stores2Res.body.data.length, 1, 'EMP-1001 must have exactly 1 assigned store');
        assert.strictEqual(stores2Res.body.data[0].id, 'STR-004', 'EMP-1001 store must be STR-004');
        passedCount++;
        console.log('PASS [Test 5]: GET /api/v1/stores returned only assigned store (STR-004) for EMP-1001');

        // Test 6: Missing Auth Header
        const noTokenRes = await request(currentPort, { path: '/api/v1/stores', method: 'GET' });
        assert.strictEqual(noTokenRes.status, 401, 'Request without token must return 401');
        passedCount++;
        console.log('PASS [Test 6]: GET /api/v1/stores without authorization header returned HTTP 401');

        // Test 7: Invalid Token
        const invalidTokenRes = await request(currentPort, {
            path: '/api/v1/stores', method: 'GET',
            headers: { 'Authorization': 'Bearer invalid_token_99999' }
        });
        assert.strictEqual(invalidTokenRes.status, 401, 'Invalid token must return HTTP 401');
        passedCount++;
        console.log('PASS [Test 7]: GET /api/v1/stores with invalid token returned HTTP 401');

        // Test 8: Expired Token
        const expiredToken = 'expired_test_token_12345';
        await new Promise((res, rej) => {
            const db = new sqlite3.Database(testDbPath);
            db.run(
                'INSERT INTO sessions (token, user_id, expires_at, is_revoked) VALUES (?, ?, ?, 0)',
                [expiredToken, 'usr-9402', Date.now() - 10000],
                (err) => { db.close(); if (err) rej(err); else res(); }
            );
        });

        const expiredRes = await request(currentPort, {
            path: '/api/v1/stores', method: 'GET',
            headers: { 'Authorization': `Bearer ${expiredToken}` }
        });
        assert.strictEqual(expiredRes.status, 401, 'Expired token must return HTTP 401');
        assert.strictEqual(expiredRes.body.message, 'Session token has expired.');
        passedCount++;
        console.log('PASS [Test 8]: GET /api/v1/stores with expired token returned HTTP 401');

        // Test 9: Logout
        const logoutRes = await request(currentPort, {
            path: '/api/v1/auth/logout', method: 'POST',
            headers: { 'Authorization': `Bearer ${token1}` }
        });
        assert.strictEqual(logoutRes.status, 200, 'Logout must return 200 OK');
        passedCount++;
        console.log(`PASS [Test 9]: POST /api/v1/auth/logout succeeded for token ${sanitizeToken(token1)}`);

        // Test 10: Revoked Token Access
        const revokedRes = await request(currentPort, {
            path: '/api/v1/stores', method: 'GET',
            headers: { 'Authorization': `Bearer ${token1}` }
        });
        assert.strictEqual(revokedRes.status, 401, 'Revoked token must return HTTP 401');
        assert.strictEqual(revokedRes.body.message, 'Token has been revoked.');
        passedCount++;
        console.log('PASS [Test 10]: GET /api/v1/stores with revoked token returned HTTP 401');

        // Test 11: Server Restart & Database Persistence (Graceful Shutdown verifying lock release)
        await stopServer(activeServer.process, lockPath);

        activeServer = await startServer(env);
        currentPort = activeServer.port;

        const postRestartRes = await request(currentPort, {
            path: '/api/v1/stores', method: 'GET',
            headers: { 'Authorization': `Bearer ${token2}` }
        });
        assert.strictEqual(postRestartRes.status, 200, 'Persistent session token must remain valid after server restart');
        assert.strictEqual(postRestartRes.body.data[0].id, 'STR-004');
        passedCount++;
        console.log('PASS [Test 11]: Database persistence verified across server restart');

        // Test 12: Database Reset Verification
        await stopServer(activeServer.process, lockPath);
        execSync('node reset.js', { cwd: mockServerDir, env, stdio: 'ignore' });
        activeServer = await startServer(env);
        currentPort = activeServer.port;

        const postResetRes = await request(currentPort, {
            path: '/api/v1/stores', method: 'GET',
            headers: { 'Authorization': `Bearer ${token2}` }
        });
        assert.strictEqual(postResetRes.status, 401, 'Reset database must invalidate previous session tokens');
        passedCount++;
        console.log('PASS [Test 12]: Database reset successfully cleared previous session state');


        // --- SECTION 2: NEGATIVE & SAFETY TESTS ---

        // Test 13: Simultaneous Lock Acquisition Rejection (Verifying exact lock conflict stderr & exit code)
        let lockConflictVerified = false;
        try {
            const secondServer = await startServer(env);
            await stopServer(secondServer.process, lockPath);
            assert.fail('Second server must not start successfully while lock is held');
        } catch (err) {
            if (err.message.includes('already locked by running server process') && err.message.includes('exited prematurely with code 1')) {
                lockConflictVerified = true;
            } else {
                throw new Error(`Test 13 failed: Unexpected error during lock conflict test: ${err.message}`);
            }
        }
        assert.ok(lockConflictVerified, 'Second server must fail startup specifically due to lock conflict in stderr');
        passedCount++;
        console.log('PASS [Test 13]: Simultaneous lock acquisition correctly rejected 2nd server startup with exact lock conflict error');

        // Test 14: Active-Server Reset Rejection
        let resetFailed = false;
        try {
            execSync('node reset.js', { cwd: mockServerDir, env, stdio: 'pipe' });
        } catch (err) {
            resetFailed = true;
            assert.strictEqual(err.status, 1, 'reset.js must exit with code 1 when server is active');
        }
        assert.ok(resetFailed, 'reset.js must fail when server is active and holding lock');
        passedCount++;
        console.log('PASS [Test 14]: reset.js correctly rejected active-server reset request');

        // Test 15: Unsafe / Path Traversal Rejection
        let unsafePathFailed = false;
        try {
            const unsafeEnv = { ...env, DB_PATH: '../mock-server-evil/database.sqlite' };
            execSync('node reset.js', { cwd: mockServerDir, env: unsafeEnv, stdio: 'pipe' });
        } catch (err) {
            unsafePathFailed = true;
            assert.strictEqual(err.status, 1, 'reset.js must exit code 1 on path outside mock-server');
        }
        assert.ok(unsafePathFailed, 'reset.js must reject path traversal / sibling-prefix escapes');
        passedCount++;
        console.log('PASS [Test 15]: Path traversal / sibling-prefix escapes correctly rejected');

        // Test 16: Real Symlink / Junction Rejection Test (Corrected with disposable DB path & explicit stderr/exit status assertions)
        const dummyTargetDir = path.join(testTmpDir, 'dummy-target-files');
        fs.mkdirSync(dummyTargetDir, { recursive: true });
        const dummyFileInTarget = path.join(dummyTargetDir, 'protected-file.txt');
        fs.writeFileSync(dummyFileInTarget, 'DO_NOT_DELETE');

        const symlinkUploadsDir = path.join(testTmpDir, 'symlink-uploads-link');
        let linkCreated = false;
        try {
            fs.symlinkSync(dummyTargetDir, symlinkUploadsDir, 'junction');
            linkCreated = true;
        } catch (symlinkErr) {
            skippedCount++;
            console.log(`SKIP [Test 16]: Platform does not support symlink/junction creation: ${symlinkErr.message}`);
        }

        if (linkCreated) {
            const test16DbPath = path.join(testTmpDir, 'test16-database.sqlite');
            const symlinkEnv = {
                ...env,
                DB_PATH: test16DbPath,
                UPLOADS_DIR: symlinkUploadsDir
            };

            let resetExitStatus = null;
            let resetStderr = '';
            try {
                execSync('node reset.js', { cwd: mockServerDir, env: symlinkEnv, stdio: 'pipe' });
            } catch (err) {
                resetExitStatus = err.status;
                if (err.stderr) {
                    resetStderr = err.stderr.toString();
                }
            }

            assert.strictEqual(resetExitStatus, 1, 'reset.js must exit with status 1 when uploads dir is a symlink/junction');
            assert.ok(
                resetStderr.includes('SECURITY ERROR') && resetStderr.includes('symbolic link'),
                `reset.js stderr must explicitly report security error about symbolic link. Got: ${resetStderr}`
            );
            assert.ok(fs.existsSync(dummyFileInTarget), 'Target files inside symlink target must remain on disk');
            assert.strictEqual(
                fs.readFileSync(dummyFileInTarget, 'utf8'),
                'DO_NOT_DELETE',
                'Target file original content must remain unchanged'
            );

            passedCount++;
            console.log('PASS [Test 16]: Symlinked uploads directory correctly rejected with exact SECURITY ERROR and target files preserved');
        }

        // Test 17: Dedicated Startup Failure Test
        let startupFailureConfirmed = false;
        let badServerChild = null;
        try {
            const badEnv = { ...env, DB_PATH: '../mock-server-evil/database.sqlite' };
            const badStartRes = await startServer(badEnv);
            badServerChild = badStartRes.process;
            await stopServer(badServerChild);
            assert.fail('Server must not start successfully with an invalid/unsafe DB_PATH outside mock-server');
        } catch (err) {
            if (badServerChild) {
                assert.fail(`Test 17 failed: Server unexpectedly started successfully with unsafe path: ${err.message}`);
            } else {
                if (err.message.includes('exited prematurely with code 1') && err.message.includes('SECURITY ERROR')) {
                    startupFailureConfirmed = true;
                } else {
                    throw new Error(`Test 17 failed with unexpected error message: ${err.message}`);
                }
            }
        }
        assert.ok(startupFailureConfirmed, 'Startup failure test must verify exact security error and nonzero child exit status');
        passedCount++;
        console.log('PASS [Test 17]: Dedicated startup failure test verified exact security error, nonzero child exit status, and safe cleanup');

        console.log(`\n=== TEST SUITE RESULTS: ${passedCount} PASSED, ${failedCount} FAILED, ${skippedCount} SKIPPED ===`);
    } catch (err) {
        failedCount++;
        preserveTempOnFailure = true;
        console.error('\nFAIL [Test Suite Error]:', err.message);
        process.exitCode = 1;
    } finally {
        if (activeServer && activeServer.process) {
            try {
                await stopServer(activeServer.process, testDbPath + '.lock');
            } catch (stopErr) {
                console.error('Teardown cleanup error:', stopErr.message);
                preserveTempOnFailure = true;
                process.exitCode = 1;
            }
        }
        if (!preserveTempOnFailure && process.exitCode !== 1) {
            try {
                fs.rmSync(testTmpDir, { recursive: true, force: true });
            } catch (rmErr) {
                console.error('Failed to remove temp dir:', rmErr.message);
                process.exitCode = 1;
            }
        } else {
            console.log(`[TEST FAIL/UNCONFIRMED TERMINATION] Preserving temporary test directory for diagnostic inspection: ${testTmpDir}`);
            process.exitCode = 1;
        }
    }
}

runSuite().then(() => {
    if (process.exitCode && process.exitCode !== 0) {
        process.exit(1);
    }
});
