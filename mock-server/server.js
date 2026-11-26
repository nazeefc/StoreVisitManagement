const express = require('express');
const sqlite3 = require('sqlite3').verbose();
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');

const mockServerDir = fs.realpathSync(__dirname);

function validateCanonicalPath(targetPath, baseDir, paramName) {
    const canonicalBaseDir = fs.realpathSync(baseDir);
    const resolvedPath = path.resolve(baseDir, targetPath);

    const relativeUnresolved = path.relative(canonicalBaseDir, resolvedPath);
    if (relativeUnresolved.startsWith('..') || path.isAbsolute(relativeUnresolved)) {
        throw new Error(`SECURITY ERROR: Target ${paramName} '${resolvedPath}' is outside mock-server directory '${canonicalBaseDir}'.`);
    }

    if (fs.existsSync(resolvedPath)) {
        if (fs.lstatSync(resolvedPath).isSymbolicLink()) {
            throw new Error(`SECURITY ERROR: Target ${paramName} '${targetPath}' is a symbolic link. Symlinks are rejected.`);
        }
        const canonicalTarget = fs.realpathSync(resolvedPath);
        const relative = path.relative(canonicalBaseDir, canonicalTarget);
        if (relative.startsWith('..') || path.isAbsolute(relative)) {
            throw new Error(`SECURITY ERROR: Target ${paramName} '${canonicalTarget}' is outside mock-server directory '${canonicalBaseDir}'.`);
        }
        return canonicalTarget;
    } else {
        const parentDir = path.dirname(resolvedPath);
        if (fs.existsSync(parentDir)) {
            if (fs.lstatSync(parentDir).isSymbolicLink()) {
                throw new Error(`SECURITY ERROR: Parent directory of ${paramName} '${parentDir}' is a symbolic link.`);
            }
            const canonicalParent = fs.realpathSync(parentDir);
            const relativeParent = path.relative(canonicalBaseDir, canonicalParent);
            if (relativeParent.startsWith('..') || path.isAbsolute(relativeParent)) {
                throw new Error(`SECURITY ERROR: Parent directory of ${paramName} '${canonicalParent}' is outside mock-server directory '${canonicalBaseDir}'.`);
            }
        }
        return resolvedPath;
    }
}

const rawDbPath = process.env.DB_PATH || path.join(__dirname, 'database.sqlite');
const rawUploadsDir = process.env.UPLOADS_DIR || path.join(__dirname, 'uploads');

const dbPath = validateCanonicalPath(rawDbPath, mockServerDir, 'database');
const uploadsDir = validateCanonicalPath(rawUploadsDir, mockServerDir, 'uploads');

if (!fs.existsSync(uploadsDir)) {
    fs.mkdirSync(uploadsDir, { recursive: true });
} else {
    if (fs.lstatSync(uploadsDir).isSymbolicLink()) {
        console.error(`SERVER STARTUP ERROR: Uploads directory '${uploadsDir}' is a symbolic link. Symlinks are rejected.`);
        process.exit(1);
    }
}

// Coordinated Exclusive Atomic Database Lock Mechanism ('wx')
const lockPath = dbPath + '.lock';
let serverLockFd;

try {
    serverLockFd = fs.openSync(lockPath, 'wx');
    fs.writeFileSync(serverLockFd, JSON.stringify({ pid: process.pid, createdAt: Date.now() }));
} catch (err) {
    let runningPid = null;
    if (fs.existsSync(lockPath)) {
        try {
            const lockContent = fs.readFileSync(lockPath, 'utf8');
            const lockData = JSON.parse(lockContent);
            if (lockData && lockData.pid) {
                try {
                    process.kill(lockData.pid, 0);
                    runningPid = lockData.pid;
                } catch (e) {
                    if (e.code === 'EPERM') runningPid = lockData.pid;
                }
            }
        } catch (e) {}
    }

    if (runningPid) {
        console.error(`SERVER STARTUP ERROR: Database '${path.basename(dbPath)}' is already locked by running server process (PID ${runningPid}).`);
    } else {
        console.error(`SERVER STARTUP ERROR: Lockfile '${lockPath}' exists. Fail closed. Manual recovery required: verify no server process is running, then manually remove lockfile.`);
    }
    process.exit(1);
}

function releaseLock() {
    if (serverLockFd !== undefined) {
        try { fs.closeSync(serverLockFd); } catch {}
        serverLockFd = undefined;
    }
    if (fs.existsSync(lockPath)) {
        try {
            const lockContent = fs.readFileSync(lockPath, 'utf8');
            const lockData = JSON.parse(lockContent);
            if (lockData && lockData.pid === process.pid) {
                fs.unlinkSync(lockPath);
            }
        } catch {}
    }
}

const db = new sqlite3.Database(dbPath);
const app = express();
const PORT = process.env.PORT !== undefined ? parseInt(process.env.PORT, 10) : 8080;
const HOST = process.env.HOST || '127.0.0.1';

app.use(express.json({ limit: '10mb' }));

// Non-destructive schema initialization
db.serialize(() => {
    db.run("PRAGMA foreign_keys = ON;");

    db.run(`
        CREATE TABLE IF NOT EXISTS server_tasks (
            id TEXT PRIMARY KEY,
            store_id TEXT NOT NULL,
            employee_id TEXT NOT NULL,
            title TEXT NOT NULL,
            description TEXT,
            category TEXT NOT NULL,
            is_completed INTEGER NOT NULL DEFAULT 0,
            is_deleted INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            FOREIGN KEY(store_id) REFERENCES server_stores(id) ON DELETE CASCADE
        )
    `);

    db.run(`
        CREATE TABLE IF NOT EXISTS server_photos (
            id TEXT PRIMARY KEY,
            task_id TEXT NOT NULL,
            local_file_path TEXT NOT NULL,
            server_filename TEXT NOT NULL,
            is_deleted INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            FOREIGN KEY(task_id) REFERENCES server_tasks(id) ON DELETE CASCADE
        )
    `);

    // A task tombstone and its child photo tombstones are one SQLite statement.
    db.run(`
        CREATE TRIGGER IF NOT EXISTS trg_server_task_tombstone_photos
        AFTER UPDATE OF is_deleted ON server_tasks
        FOR EACH ROW WHEN NEW.is_deleted = 1
        BEGIN
            UPDATE server_photos SET is_deleted = 1 WHERE task_id = NEW.id;
        END
    `);
});

let simulateFailure = false;

function checkFailureSimulation(req, res, next) {
    if (simulateFailure || req.headers['x-simulate-failure'] === 'true') {
        simulateFailure = false;
        return res.status(500).json({ status: 'error', message: 'Simulated 500 server error for retry testing.' });
    }
    next();
}

function hashPassword(password, salt) {
    return crypto.scryptSync(password, salt, 64).toString('hex');
}

// Helper to authenticate Bearer token
function authenticateToken(req, res, next) {
    const authHeader = req.headers['authorization'];
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ status: 'error', message: 'Authorization header missing or invalid.' });
    }

    const token = authHeader.substring(7).trim();
    const now = Date.now();

    const sql = `
        SELECT s.token, s.expires_at, s.is_revoked, u.id as user_id, u.employee_id, u.name, u.role
        FROM sessions s
        JOIN users u ON s.user_id = u.id
        WHERE s.token = ?
    `;

    db.get(sql, [token], (err, row) => {
        if (err) {
            return res.status(500).json({ status: 'error', message: 'Database error during authentication.' });
        }
        if (!row) {
            return res.status(401).json({ status: 'error', message: 'Invalid or expired token.' });
        }
        if (row.is_revoked === 1) {
            return res.status(401).json({ status: 'error', message: 'Token has been revoked.' });
        }
        if (row.expires_at < now) {
            return res.status(401).json({ status: 'error', message: 'Session token has expired.' });
        }

        req.user = row;
        next();
    });
}

// 1. Health Endpoint
app.get('/api/v1/health', (req, res) => {
    res.status(200).json({ status: 'ok', timestamp: Date.now() });
});

// 2. Auth Login Endpoint
app.post('/api/v1/auth/login', (req, res) => {
    const { employee_id, password } = req.body;

    if (!employee_id || !password) {
        return res.status(400).json({ status: 'error', message: 'Employee ID and password are required.' });
    }

    db.get('SELECT * FROM users WHERE employee_id = ?', [employee_id], (err, user) => {
        if (err) {
            return res.status(500).json({ status: 'error', message: 'Database query error.' });
        }
        if (!user) {
            return res.status(401).json({ status: 'error', message: 'Invalid employee ID or password.' });
        }

        const computedHash = hashPassword(password, user.salt);
        if (computedHash !== user.password_hash) {
            return res.status(401).json({ status: 'error', message: 'Invalid employee ID or password.' });
        }

        const token = crypto.randomBytes(32).toString('hex');
        const expiresAt = Date.now() + 86400000; // 24 hours from now

        db.run(
            'INSERT INTO sessions (token, user_id, expires_at, is_revoked) VALUES (?, ?, ?, 0)',
            [token, user.id, expiresAt],
            (err) => {
                if (err) {
                    return res.status(500).json({ status: 'error', message: 'Failed to create session token.' });
                }

                res.status(200).json({
                    status: 'success',
                    data: {
                        token: token,
                        expires_at: expiresAt,
                        employee: {
                            id: user.employee_id,
                            name: user.name,
                            email: `${user.employee_id.toLowerCase()}@synthetic-storevisit.com`,
                            role: user.role
                        }
                    }
                });
            }
        );
    });
});

// 3. Auth Logout Endpoint
app.post('/api/v1/auth/logout', authenticateToken, (req, res) => {
    const token = req.user.token;

    db.run('UPDATE sessions SET is_revoked = 1 WHERE token = ?', [token], (err) => {
        if (err) {
            return res.status(500).json({ status: 'error', message: 'Failed to revoke session token.' });
        }
        res.status(200).json({ status: 'success', message: 'Successfully logged out.' });
    });
});

// 4. Assigned Stores Endpoint
app.get('/api/v1/stores', authenticateToken, (req, res) => {
    const employeeId = req.user.employee_id;

    const sql = `
        SELECT id, name, code, address, lat as latitude, lng as longitude, assigned_employee_id
        FROM server_stores
        WHERE assigned_employee_id = ?
        ORDER BY name ASC
    `;

    db.all(sql, [employeeId], (err, rows) => {
        if (err) {
            return res.status(500).json({ status: 'error', message: 'Failed to fetch assigned stores.' });
        }

        res.status(200).json({
            status: 'success',
            data: rows
        });
    });
});

// 5. Tasks Sync Endpoint (Atomic SQLite ON CONFLICT Upserts with Ownership & Timestamp Guards)
app.post('/api/v1/tasks/sync', authenticateToken, checkFailureSimulation, (req, res) => {
    const employeeId = req.user.employee_id;
    const tasks = req.body.tasks;

    if (!Array.isArray(tasks) || tasks.length === 0) {
        return res.status(400).json({ status: 'error', message: 'Tasks array is required.' });
    }

    const syncedTaskIds = [];
    let pendingCount = tasks.length;
    let sendError = false;

    const upsertSql = `
        INSERT INTO server_tasks (id, store_id, employee_id, title, description, category, is_completed, is_deleted, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            title = excluded.title,
            description = excluded.description,
            category = excluded.category,
            is_completed = excluded.is_completed,
            is_deleted = excluded.is_deleted,
            updated_at = excluded.updated_at
        WHERE excluded.updated_at > server_tasks.updated_at AND excluded.employee_id = server_tasks.employee_id
    `;

    tasks.forEach((task) => {
        if (!task.id || !task.store_id || !task.title ||
            !Number.isFinite(Number(task.created_at)) ||
            !Number.isFinite(Number(task.updated_at))) {
            if (!sendError) {
                sendError = true;
                return res.status(400).json({ status: 'error', message: 'Invalid task object format.' });
            }
            return;
        }

        const description = task.description || '';
        const category = task.category || 'INVENTORY';
        const createdAt = Number(task.created_at);
        const updatedAt = Number(task.updated_at);
        const isCompleted = task.is_completed ? 1 : 0;
        const isDeleted = task.is_deleted ? 1 : 0;

        // Validate store ownership for current employee
        db.get('SELECT id FROM server_stores WHERE id = ? AND assigned_employee_id = ?', [task.store_id, employeeId], (err, store) => {
            if (err) {
                if (!sendError) {
                    sendError = true;
                    return res.status(500).json({ status: 'error', message: 'Database error during store validation.' });
                }
                return;
            }
            if (!store) {
                if (!sendError) {
                    sendError = true;
                    return res.status(403).json({ status: 'error', message: `Store ${task.store_id} not assigned to employee ${employeeId}.` });
                }
                return;
            }

            // A strictly newer updatedAt replaces the server row. Exact retries are
            // acknowledged below; stale and conflicting equal-timestamp payloads are rejected.
            db.run(
                upsertSql,
                [
                    task.id,
                    task.store_id,
                    employeeId,
                    task.title,
                    description,
                    category,
                    isCompleted,
                    isDeleted,
                    createdAt,
                    updatedAt
                ],
                function(err) {
                    if (err) {
                        if (!sendError) {
                            sendError = true;
                            return res.status(500).json({ status: 'error', message: 'Failed to sync task in database.' });
                        }
                        return;
                    }

                    if (this.changes > 0) {
                        syncedTaskIds.push(task.id);
                    } else {
                        db.get(
                            'SELECT id, store_id, employee_id, title, description, category, is_completed, is_deleted, created_at, updated_at FROM server_tasks WHERE id = ?',
                            [task.id],
                            (lookupError, existing) => {
                                if (lookupError) {
                                    if (!sendError) {
                                        sendError = true;
                                        return res.status(500).json({ status: 'error', message: 'Database error while checking task revision.' });
                                    }
                                    return;
                                }
                                if (existing && existing.employee_id !== employeeId) {
                                    if (!sendError) {
                                        sendError = true;
                                        return res.status(403).json({ status: 'error', message: 'Task belongs to another employee.' });
                                    }
                                    return;
                                }

                                const identicalRetry = existing &&
                                    existing.store_id === task.store_id &&
                                    existing.employee_id === employeeId &&
                                    existing.title === task.title &&
                                    (existing.description || '') === description &&
                                    existing.category === category &&
                                    existing.is_completed === isCompleted &&
                                    existing.is_deleted === isDeleted &&
                                    existing.created_at === createdAt &&
                                    existing.updated_at === updatedAt;

                                if (identicalRetry) {
                                    syncedTaskIds.push(task.id);
                                } else {
                                    if (!sendError) {
                                        sendError = true;
                                        return res.status(409).json({
                                            status: 'error',
                                            message: 'Stale task update or conflicting payload for the same updated_at.'
                                        });
                                    }
                                    return;
                                }

                                if (--pendingCount === 0 && !sendError && !res.headersSent) {
                                    res.status(200).json({ status: 'success', data: { synced_task_ids: syncedTaskIds } });
                                }
                            }
                        );
                        return;
                    }

                    if (--pendingCount === 0 && !sendError && !res.headersSent) {
                        res.status(200).json({ status: 'success', data: { synced_task_ids: syncedTaskIds } });
                    }
                }
            );
        });
    });
});

// 6. Photos Upload Endpoint (UUID Validation & Idempotent File Writing)
app.post('/api/v1/photos/upload', authenticateToken, checkFailureSimulation, (req, res) => {
    const employeeId = req.user.employee_id;
    const { photo_id, task_id, image_base64, local_file_path, created_at } = req.body;

    if (!photo_id || !task_id) {
        return res.status(400).json({ status: 'error', message: 'photo_id and task_id are required.' });
    }

    // Validate photo_id format (prevent path traversal)
    if (!/^[a-zA-Z0-9_-]+$/.test(photo_id)) {
        return res.status(400).json({ status: 'error', message: 'Invalid photo_id format.' });
    }

    if (!image_base64 || typeof image_base64 !== 'string' || image_base64.trim().length === 0) {
        return res.status(400).json({ status: 'error', message: 'Valid image_base64 data is required.' });
    }

    const serverFilename = `upload_${photo_id}.jpg`;
    const destPath = path.join(uploadsDir, serverFilename);

    // Step 1: Check existing photo row BEFORE writing file to disk
    db.get('SELECT id, task_id FROM server_photos WHERE id = ?', [photo_id], (err, existingPhoto) => {
        if (err) {
            return res.status(500).json({ status: 'error', message: 'Database query error during photo check.' });
        }

        if (existingPhoto) {
            // Photo already exists! Verify parent task belongs to current employee
            db.get('SELECT id FROM server_tasks WHERE id = ? AND employee_id = ?', [existingPhoto.task_id, employeeId], (err, validTask) => {
                if (err) return res.status(500).json({ status: 'error', message: 'Database query error.' });
                if (!validTask) {
                    return res.status(403).json({ status: 'error', message: 'Unauthorized photo retry attempt.' });
                }

                // Idempotent retry acknowledgement: return 200 without overwriting file or metadata
                return res.status(200).json({
                    status: 'success',
                    data: {
                        photo_id: photo_id,
                        server_filename: serverFilename
                    }
                });
            });
            return;
        }

        // Step 2: New Photo Upload -> Validate parent task exists and belongs to current employee
        db.get('SELECT id FROM server_tasks WHERE id = ? AND employee_id = ? AND is_deleted = 0', [task_id, employeeId], (err, task) => {
            if (err) {
                return res.status(500).json({ status: 'error', message: 'Database error during parent task validation.' });
            }
            if (!task) {
                return res.status(400).json({ status: 'error', message: `Parent task ${task_id} not found or unauthorized for employee ${employeeId}.` });
            }

            // Write file ONLY if destination file doesn't already exist and after validation
            if (!fs.existsSync(destPath)) {
                try {
                    const buffer = Buffer.from(image_base64, 'base64');
                    if (buffer.length === 0) {
                        return res.status(400).json({ status: 'error', message: 'Invalid or empty image binary data.' });
                    }
                    fs.writeFileSync(destPath, buffer);
                } catch (e) {
                    return res.status(500).json({ status: 'error', message: 'Failed to write photo file to uploads directory.' });
                }
            }

            db.run(
                'INSERT INTO server_photos (id, task_id, local_file_path, server_filename, is_deleted, created_at) VALUES (?, ?, ?, ?, 0, ?)',
                [photo_id, task_id, local_file_path || '', serverFilename, created_at || Date.now()],
                (err) => {
                    if (err) {
                        return res.status(500).json({ status: 'error', message: 'Failed to record photo metadata in database.' });
                    }
                    res.status(200).json({
                        status: 'success',
                        data: {
                            photo_id: photo_id,
                            server_filename: serverFilename
                        }
                    });
                }
            );
        });
    });
});

// 7. Minimal Idempotent Task & Photo Deletion Endpoints
app.post('/api/v1/tasks/delete', authenticateToken, checkFailureSimulation, (req, res) => {
    const employeeId = req.user.employee_id;
    const { task_id } = req.body;

    if (!task_id) {
        return res.status(400).json({ status: 'error', message: 'task_id is required.' });
    }

    db.get('SELECT id FROM server_tasks WHERE id = ? AND employee_id = ?', [task_id, employeeId], (err, task) => {
        if (err) return res.status(500).json({ status: 'error', message: 'Database error.' });
        if (!task) return res.status(404).json({ status: 'error', message: 'Task not found or unauthorized.' });

        db.run('UPDATE server_tasks SET is_deleted = 1 WHERE id = ?', [task_id], (err) => {
            if (err) return res.status(500).json({ status: 'error', message: 'Failed to delete task.' });
            res.status(200).json({ status: 'success', message: 'Task marked deleted successfully.' });
        });
    });
});

app.post('/api/v1/photos/delete', authenticateToken, checkFailureSimulation, (req, res) => {
    const employeeId = req.user.employee_id;
    const { photo_id } = req.body;

    if (!photo_id) {
        return res.status(400).json({ status: 'error', message: 'photo_id is required.' });
    }

    db.get('SELECT p.id FROM server_photos p JOIN server_tasks t ON p.task_id = t.id WHERE p.id = ? AND t.employee_id = ?', [photo_id, employeeId], (err, photo) => {
        if (err) return res.status(500).json({ status: 'error', message: 'Database error.' });
        if (!photo) return res.status(404).json({ status: 'error', message: 'Photo not found or unauthorized.' });

        db.run('UPDATE server_photos SET is_deleted = 1 WHERE id = ?', [photo_id], (err) => {
            if (err) return res.status(500).json({ status: 'error', message: 'Failed to delete photo.' });
            res.status(200).json({ status: 'success', message: 'Photo marked deleted successfully.' });
        });
    });
});

// 8. Tasks & Photos GET Endpoints (Scoped to Employee and Excluding Deleted Tasks)
app.get('/api/v1/tasks', authenticateToken, (req, res) => {
    const employeeId = req.user.employee_id;
    db.all('SELECT * FROM server_tasks WHERE employee_id = ? AND is_deleted = 0 ORDER BY created_at DESC', [employeeId], (err, rows) => {
        if (err) return res.status(500).json({ status: 'error', message: 'Database error.' });
        res.status(200).json({ status: 'success', data: rows });
    });
});

app.get('/api/v1/photos', authenticateToken, (req, res) => {
    const employeeId = req.user.employee_id;
    db.all(`
        SELECT p.* FROM server_photos p
        JOIN server_tasks t ON p.task_id = t.id
        WHERE t.employee_id = ? AND p.is_deleted = 0 AND t.is_deleted = 0
        ORDER BY p.created_at DESC
    `, [employeeId], (err, rows) => {
        if (err) return res.status(500).json({ status: 'error', message: 'Database error.' });
        res.status(200).json({ status: 'success', data: rows });
    });
});

// 9. Debug Toggle Failure Endpoint
app.post('/api/v1/debug/toggle-failure', (req, res) => {
    simulateFailure = true;
    res.status(200).json({ status: 'success', message: 'Failure simulation enabled for next sync request.' });
});

// Start Server listening on HOST:PORT
const server = app.listen(PORT, HOST, () => {
    const actualPort = server.address().port;
    console.log(`Standalone Mock REST Server running on http://${HOST}:${actualPort}`);
});

function shutdown() {
    server.close(() => {
        db.close(() => {
            releaseLock();
            process.exit(0);
        });
    });
}

process.on('message', (msg) => {
    if (msg === 'SHUTDOWN') {
        shutdown();
    }
});

process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
process.on('exit', () => {
    releaseLock();
});
