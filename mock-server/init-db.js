const sqlite3 = require('sqlite3').verbose();
const path = require('path');

const dbPath = process.env.DB_PATH || path.join(__dirname, 'database.sqlite');
const db = new sqlite3.Database(dbPath);

db.serialize(() => {
    // Enable foreign keys
    db.run("PRAGMA foreign_keys = ON;");

    // Users table
    db.run(`
        CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            employee_id TEXT UNIQUE NOT NULL,
            password_hash TEXT NOT NULL,
            salt TEXT NOT NULL,
            name TEXT NOT NULL,
            role TEXT NOT NULL
        )
    `);

    // Sessions table
    db.run(`
        CREATE TABLE IF NOT EXISTS sessions (
            token TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            is_revoked INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
        )
    `);

    // Stores table
    db.run(`
        CREATE TABLE IF NOT EXISTS server_stores (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL,
            code TEXT NOT NULL,
            address TEXT NOT NULL,
            lat REAL NOT NULL,
            lng REAL NOT NULL,
            assigned_employee_id TEXT NOT NULL,
            FOREIGN KEY(assigned_employee_id) REFERENCES users(employee_id) ON DELETE CASCADE
        )
    `);

    // Server Tasks table
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

    // Server Photos table
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

    console.log(`Database tables initialized successfully at ${path.basename(dbPath)}.`);
});

db.close();
