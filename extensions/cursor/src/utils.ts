import * as vscode from 'vscode';
import * as path from 'path';
import * as os from 'os';
import * as fs from 'fs';  
import { v4 as uuidv4 } from 'uuid';
import { captureException } from './sentry';

// Create output channels for logging
let outputChannel: vscode.OutputChannel;
let debugOutputChannel: vscode.OutputChannel;

export function initializeLogging() {
  outputChannel = vscode.window.createOutputChannel('Cursor DB Finder');
  outputChannel.show(); // This will show the output panel automatically
}

export function initializeDebugLogging() {
  if (!debugOutputChannel) {
    debugOutputChannel = vscode.window.createOutputChannel('Opik Debug');
  }
}

export function log(message: string) {
  const timestamp = new Date().toISOString();
  const logMessage = `[${timestamp}] ${message}`;
  
  // Log to both console and VS Code output channel
  console.log(logMessage);
  if (outputChannel) {
    outputChannel.appendLine(logMessage);
  }
}

export function debugLog(message: string, data?: any) {
  const config = vscode.workspace.getConfiguration();
  const debugEnabled = config.get<boolean>('opik.enableDebugLogs') ?? false;
  
  if (!debugEnabled) {
    return;
  }
  
  if (!debugOutputChannel) {
    initializeDebugLogging();
  }
  
  const timestamp = new Date().toISOString();
  const logMessage = `[${timestamp}] ${message}`;
  
  console.log(logMessage, data ? data : '');
  debugOutputChannel.appendLine(logMessage);
  
  if (data !== undefined) {
    const dataStr = typeof data === 'object' ? JSON.stringify(data, null, 2) : String(data);
    debugOutputChannel.appendLine(dataStr);
  }
  
  debugOutputChannel.appendLine(''); // Empty line for readability
}

export function showDebugChannel() {
  if (debugOutputChannel) {
    debugOutputChannel.show();
  }
}

export function getDefaultVSCodeUserDataPath(context?: vscode.ExtensionContext): string {
  if (context && context.globalStorageUri) {
    // Move to the right parent directory as we are in <.../Cursor/User/globalStorage>
    const parentPath = path.join(context.globalStorageUri.fsPath, '..', '..', '..');
    return parentPath;
  } else {
    throw new Error('Unsupported platform');
  }
}

export function findFolder(basePath: string, folderName: string): string[] {
  let results: string[] = [];
  const files = fs.readdirSync(basePath);

  for (const file of files) {
    const fullPath = path.join(basePath, file);
    const stat = fs.statSync(fullPath);

    if (stat.isDirectory()) {
      if (file === folderName) {
        results.push(fullPath);
      } else {
        results = results.concat(findFolder(fullPath, folderName));
      }
    }
  }

  return results;
}


export function readJsonFile(filePath: string): any {
    try {
      const fileContents = fs.readFileSync(filePath, 'utf-8');
      return JSON.parse(fileContents);
    } catch (error) {
      captureException(error);
      console.error(`Error reading or parsing ${filePath}:`, error);
      return null;
    }
  }

export function getOrCreateUUID(context: vscode.ExtensionContext): string {
    // Check if there's an existing UUID stored in global state
    let uniqueId = context.globalState.get<string>('uniqueId');
  
    // If not, generate a new UUID and store it
    if (!uniqueId) {
        uniqueId = uuidv4();
        context.globalState.update('uniqueId', uniqueId);
        console.log(`Generated new UUID: ${uniqueId}`);
    } else {
        console.log(`Existing UUID: ${uniqueId}`);
    }
    return uniqueId;
}

export function getOpikApiKey(): string | undefined {
  // First check VS Code configuration
  const config = vscode.workspace.getConfiguration();
  const vsCodeApiKey: string | undefined = config.get('opik.apiKey');
  
  if (vsCodeApiKey && vsCodeApiKey.trim()) {
    return vsCodeApiKey.trim();
  }
  
  // If not found, check ~/.opik.config file
  try {
    const opikConfigPath = path.join(os.homedir(), '.opik.config');
    
    if (fs.existsSync(opikConfigPath)) {
      const configContent = fs.readFileSync(opikConfigPath, 'utf-8');
      
      // Parse the INI-style config file
      const lines = configContent.split('\n');
      let inOpikSection = false;
      
      for (const line of lines) {
        const trimmedLine = line.trim();
        
        // Check if we're entering the [opik] section
        if (trimmedLine === '[opik]') {
          inOpikSection = true;
          continue;
        }
        
        // Check if we're entering a different section
        if (trimmedLine.startsWith('[') && trimmedLine.endsWith(']') && trimmedLine !== '[opik]') {
          inOpikSection = false;
          continue;
        }
        
        // If we're in the opik section and find api_key
        if (inOpikSection && trimmedLine.startsWith('api_key')) {
          const parts = trimmedLine.split('=');
          if (parts.length === 2) {
            const apiKey = parts[1].trim();
            if (apiKey) {
              return apiKey;
            }
          }
        }
      }
    }
  } catch (error) {
    // Don't capture this as it's expected to fail sometimes
    console.log('Could not read ~/.opik.config file:', error);
  }
  
  return undefined;
}
