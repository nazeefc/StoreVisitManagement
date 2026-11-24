const sqlite3 = require('sqlite3').verbose();
const crypto = require('crypto');
const path = require('path');

const dbPath = process.env.DB_PATH || path.join(__dirname, 'database.sqlite');
const db = new sqlite3.Database(dbPath);

function hashPassword(password, salt) {
    return crypto.scryptSync(password, salt, 64).toString('hex');
}

db.serialize(() => {
    db.run("PRAGMA foreign_keys = ON;");

    // Insert synthetic employees
    const user1Salt = crypto.randomBytes(16).toString('hex');
    const user1Hash = hashPassword('SyntheticPassword123!', user1Salt);

    const user2Salt = crypto.randomBytes(16).toString('hex');
    const user2Hash = hashPassword('OtherPassword123!', user2Salt);

    const userStmt = db.prepare(`
        INSERT OR REPLACE INTO users (id, employee_id, password_hash, salt, name, role)
        VALUES (?, ?, ?, ?, ?, ?)
    `);

    userStmt.run('usr-9402', 'EMP-9402', user1Hash, user1Salt, 'Jane Doe', 'FIELD_REPRESENTATIVE');
    userStmt.run('usr-1001', 'EMP-1001', user2Hash, user2Salt, 'John Smith', 'FIELD_REPRESENTATIVE');
    userStmt.finalize();

    // Insert assigned stores
    const storeStmt = db.prepare(`
        INSERT OR REPLACE INTO server_stores (id, name, code, address, lat, lng, assigned_employee_id)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    `);

    // Stores for EMP-9402
    storeStmt.run('STR-001', 'Metro Market Downtown', 'MM-101', '123 Main St, Sector 4', 37.7749, -122.4194, 'EMP-9402');
    storeStmt.run('STR-002', 'SuperCenter Westside', 'SC-204', '456 West Blvd, Bay Area', 37.7833, -122.4167, 'EMP-9402');
    storeStmt.run('STR-003', 'Apex Express Bay', 'AE-309', '789 Harbor Rd, Suite 12', 37.7650, -122.4210, 'EMP-9402');

    // Store for EMP-1001 (Verification that EMP-9402 does not see EMP-1001's store)
    storeStmt.run('STR-004', 'Northside Plaza', 'NP-401', '999 North Ave', 37.8000, -122.4000, 'EMP-1001');

    storeStmt.finalize();

    console.log(`Database seeded successfully at ${path.basename(dbPath)}.`);
});

db.close();
