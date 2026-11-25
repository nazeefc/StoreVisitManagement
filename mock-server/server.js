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

app.use(express.json());

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
