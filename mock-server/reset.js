const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

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

let targetDbPath, targetUploadsDir;

try {
    const rawDbPath = process.env.DB_PATH || path.join(__dirname, 'database.sqlite');
    const rawUploadsDir = process.env.UPLOADS_DIR || path.join(__dirname, 'uploads');

    targetDbPath = validateCanonicalPath(rawDbPath, mockServerDir, 'database');
    targetUploadsDir = validateCanonicalPath(rawUploadsDir, mockServerDir, 'uploads');
} catch (err) {
    console.error(`RESET ERROR: ${err.message}`);
    process.exit(1);
}

// Coordinated Exclusive Atomic Database Lock Mechanism ('wx')
const lockPath = targetDbPath + '.lock';
let resetLockFd;

try {
    resetLockFd = fs.openSync(lockPath, 'wx');
    fs.writeFileSync(resetLockFd, JSON.stringify({ pid: process.pid, isResetting: true, createdAt: Date.now() }));
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
        console.error(`RESET ERROR: Cannot reset database because server process (PID ${runningPid}) is running and holding lock '${lockPath}'. Stop the server first.`);
    } else {
        console.error(`RESET ERROR: Lockfile '${lockPath}' exists. Fail closed. Manual recovery required: verify no server process is running, then manually remove lockfile.`);
    }
    process.exit(1);
}

function releaseResetLock() {
    if (resetLockFd !== undefined) {
        try { fs.closeSync(resetLockFd); } catch {}
        resetLockFd = undefined;
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

// Delete database files safely
const baseDbPath = targetDbPath;
const dbFilesToDelete = [
    baseDbPath,
    `${baseDbPath}-journal`,
    `${baseDbPath}-wal`,
    `${baseDbPath}-shm`
];

dbFilesToDelete.forEach(file => {
    if (fs.existsSync(file)) {
        try {
            if (fs.lstatSync(file).isSymbolicLink()) {
                console.error(`RESET ERROR: Refusing to delete symbolic link '${file}'.`);
                releaseResetLock();
                process.exit(1);
            }
            fs.unlinkSync(file);
            console.log(`Deleted database file: ${path.basename(file)}`);
        } catch (err) {
            console.error(`RESET ERROR: Failed to delete database file '${file}':`, err.message);
            releaseResetLock();
            process.exit(1);
        }
    }
});

// Safely clean upload files
if (fs.existsSync(targetUploadsDir)) {
    try {
        if (fs.lstatSync(targetUploadsDir).isSymbolicLink()) {
            console.error(`RESET ERROR: Upload directory '${targetUploadsDir}' is a symbolic link. Symlinks are rejected.`);
            releaseResetLock();
            process.exit(1);
        }
        const files = fs.readdirSync(targetUploadsDir);
        files.forEach(file => {
            if (file !== '.gitkeep') {
                const filePath = path.join(targetUploadsDir, file);
                const lstat = fs.lstatSync(filePath);
                if (lstat.isSymbolicLink()) {
                    console.error(`RESET ERROR: Refusing to delete symbolic link inside uploads '${file}'.`);
                    releaseResetLock();
                    process.exit(1);
                }
                if (lstat.isFile()) {
                    fs.unlinkSync(filePath);
                    console.log(`Deleted upload file: ${file}`);
                }
            }
        });
    } catch (err) {
        console.error(`RESET ERROR: Failed to clean upload directory '${targetUploadsDir}':`, err.message);
        releaseResetLock();
        process.exit(1);
    }
} else {
    try {
        fs.mkdirSync(targetUploadsDir, { recursive: true });
    } catch (err) {
        console.error(`RESET ERROR: Failed to create upload directory '${targetUploadsDir}':`, err.message);
        releaseResetLock();
        process.exit(1);
    }
}

// Re-initialize and seed database
try {
    const env = { ...process.env, DB_PATH: targetDbPath, UPLOADS_DIR: targetUploadsDir };
    console.log('Re-initializing database schema...');
    execSync('node init-db.js', { stdio: 'inherit', cwd: mockServerDir, env });

    console.log('Seeding database with synthetic data...');
    execSync('node seed.js', { stdio: 'inherit', cwd: mockServerDir, env });

    releaseResetLock();
    console.log('Database reset complete.');
} catch (err) {
    console.error('RESET ERROR: Failed during database initialization or seeding:', err.message);
    releaseResetLock();
    process.exit(1);
}
