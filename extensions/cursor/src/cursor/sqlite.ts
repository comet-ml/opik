import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { execFile } from 'child_process';
import { promisify } from 'util';

const execFileAsync = promisify(execFile);

/**
 * Find the sqlite3 binary on the system
 * Checks common installation paths for macOS, Linux, and Windows
 * @returns Path to sqlite3 binary
 * @throws Error if sqlite3 is not found
 */
export function findSqlite3Binary(): string {
    const platform = process.platform;
    
    if (platform === 'win32') {
        // On Windows, we'll try to use 'sqlite3.exe' from PATH
        // The 'where' command will be handled by execFile
        return 'sqlite3.exe';
    }
    
    // Common paths for macOS and Linux
    const commonPaths = [
        '/usr/bin/sqlite3',
        '/usr/local/bin/sqlite3',
        '/opt/homebrew/bin/sqlite3', // macOS Homebrew on Apple Silicon
        '/opt/local/bin/sqlite3',     // MacPorts
    ];
    
    // Check each path
    for (const binPath of commonPaths) {
        if (fs.existsSync(binPath)) {
            return binPath;
        }
    }
    
    // If not found in common paths, try 'sqlite3' from PATH
    // This will work if sqlite3 is in the user's PATH
    return 'sqlite3';
}

/**
 * Execute a SQL query against a SQLite database using the native sqlite3 CLI
 * @param dbPath Path to the SQLite database file
 * @param query SQL query to execute
 * @returns Array of objects where each object represents a row with column names as keys
 * @throws Error if sqlite3 is not found or query execution fails
 */
export async function executeQuery(dbPath: string, query: string): Promise<any[]> {
    // Find sqlite3 binary
    const sqlite3Binary = findSqlite3Binary();
    
    // Verify database file exists
    if (!fs.existsSync(dbPath)) {
        throw new Error(`Database file not found: ${dbPath}`);
    }
    
    try {
        // Execute sqlite3 with JSON output mode
        // -json flag outputs results as JSON array
        // -readonly opens database in read-only mode (safer)
        const { stdout, stderr } = await execFileAsync(
            sqlite3Binary,
            [
                '-json',        // Output as JSON
                '-readonly',    // Read-only mode
                dbPath,         // Database file path
                query           // SQL query
            ],
            {
                maxBuffer: 100 * 1024 * 1024, // 100MB buffer for large result sets
                timeout: 30000 // 30 second timeout
            }
        );
        
        // Check for errors in stderr
        if (stderr && stderr.trim()) {
            console.warn(`SQLite stderr: ${stderr}`);
        }
        
        // Parse JSON output
        if (!stdout || stdout.trim() === '') {
            return [];
        }
        
        try {
            const results = JSON.parse(stdout);
            return Array.isArray(results) ? results : [];
        } catch (parseError) {
            throw new Error(`Failed to parse SQLite JSON output: ${parseError}`);
        }
        
    } catch (error: any) {
        // Enhance error message for common issues
        if (error.code === 'ENOENT') {
            throw new Error(
                'SQLite3 binary not found. Please install SQLite3:\n' +
                '  • macOS: Already installed or use "brew install sqlite3"\n' +
                '  • Linux: Use "sudo apt-get install sqlite3" or "sudo yum install sqlite"\n' +
                '  • Windows: Download from https://www.sqlite.org/download.html'
            );
        }
        
        if (error.code === 'ETIMEDOUT') {
            throw new Error(`SQLite query timeout after 30 seconds: ${query.substring(0, 100)}...`);
        }
        
        // Re-throw with context
        throw new Error(`SQLite query failed: ${error.message}\nQuery: ${query.substring(0, 200)}...`);
    }
}

/**
 * Create a temporary copy of a SQLite database file
 * This avoids "database is locked" errors when the original file is in use
 * @param dbPath Path to the original SQLite database file
 * @returns Path to the temporary database copy
 * @throws Error if copy fails
 */
export function createTempDatabaseCopy(dbPath: string): string {
    // Verify source database exists
    if (!fs.existsSync(dbPath)) {
        throw new Error(`Database file not found: ${dbPath}`);
    }
    
    // Create a temporary directory
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sqlite-'));
    const tempDbPath = path.join(tempDir, 'temp.db');
    
    // Copy the database file
    fs.copyFileSync(dbPath, tempDbPath);
    
    return tempDbPath;
}

/**
 * Delete a temporary database file and its directory
 * @param tempDbPath Path to the temporary database file
 */
export function cleanupTempDatabase(tempDbPath: string): void {
    try {
        if (fs.existsSync(tempDbPath)) {
            fs.unlinkSync(tempDbPath);
            
            // Remove the temp directory if it's empty
            const tempDir = path.dirname(tempDbPath);
            if (fs.existsSync(tempDir)) {
                fs.rmdirSync(tempDir);
            }
        }
    } catch (error) {
        // Log but don't throw - cleanup failures shouldn't break the flow
        console.warn(`Failed to cleanup temporary database: ${error}`);
    }
}

/**
 * Execute a SQL query with pagination to handle large result sets
 * Fetches results in batches to avoid maxBuffer overflow issues
 * @param dbPath Path to the SQLite database file
 * @param query SQL query to execute (should not contain LIMIT/OFFSET)
 * @param batchSize Number of rows to fetch per batch (default: 100)
 * @returns Array of objects where each object represents a row with column names as keys
 * @throws Error if sqlite3 is not found or query execution fails
 */
export async function executeQueryPaginated(
    dbPath: string, 
    query: string, 
    batchSize: number = 100
): Promise<any[]> {
    const allResults: any[] = [];
    let offset = 0;
    
    // Remove any trailing semicolon from the query
    const baseQuery = query.trim().replace(/;$/, '');
    
    while (true) {
        // Add LIMIT and OFFSET to the query
        const paginatedQuery = `${baseQuery} LIMIT ${batchSize} OFFSET ${offset}`;
        
        // Fetch the batch
        const batch = await executeQuery(dbPath, paginatedQuery);
        
        // Add batch results to the accumulated results
        allResults.push(...batch);
        
        // If we got fewer results than the batch size, we've reached the end
        if (batch.length < batchSize) {
            break;
        }
        
        // Move to the next batch
        offset += batchSize;
    }
    
    return allResults;
}

