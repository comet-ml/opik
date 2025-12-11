import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { getDefaultVSCodeUserDataPath, getOrCreateUUID, getOpikApiKey } from './utils';
import { CursorService } from './cursor/cursorService';
import { initializeSentry, captureException } from './sentry';
import { MCPService } from './mcp/mcpService';
import { updateStatusBar, showInitialApiKeyWarning } from './ui';
import { resetExtensionState } from './state';

export function activate(context: vscode.ExtensionContext) {
  // Get or create user UUID for tracking
  const userId = getOrCreateUUID(context);
  const mcpService = new MCPService(context);

  // Initialize Sentry with user context
  initializeSentry(context, userId);

  try {
    console.log('Opik extension is now active!');
    
    // Initial status bar update
    updateStatusBar(context);
    mcpService.registerServer();

    // Track missing API key during extension initialization
    const initialApiKey = getOpikApiKey();
    if (!initialApiKey) {
      const error = new Error("Extension initialized without API key configured");
      captureException(error);
    }

    // Listen for configuration changes
    const configChangeListener = vscode.workspace.onDidChangeConfiguration(async event => {
      // Re-register MCP server if any relevant settings change
      if (event.affectsConfiguration('opik.apiKey') ||
          event.affectsConfiguration('opik.apiUrl') ||
          event.affectsConfiguration('opik.workspace') ||
          event.affectsConfiguration('opik.projectName') ||
          event.affectsConfiguration('opik.mcp.enabled')) {
        updateStatusBar(context);
        await mcpService.registerServer();
      }
    });
    context.subscriptions.push(configChangeListener);

    // Register reset command
    const resetCommand = vscode.commands.registerCommand('opik.resetState', () => {
      resetExtensionState(context);
      vscode.window.showInformationMessage('Opik: Extension state has been reset. Recent conversations will be re-synced.');
      console.log('🔄 Extension state reset - will re-sync recent conversations');
    });
    context.subscriptions.push(resetCommand);

    const opikConfigPath = path.join(os.homedir(), '.opik.config');
    if (fs.existsSync(opikConfigPath)) {
      const configWatcher = fs.watch(opikConfigPath, async (eventType) => {
          if (eventType === 'change') {
            updateStatusBar(context);
            await mcpService.registerServer();
          }
      });
      context.subscriptions.push({
        dispose: () => configWatcher.close()
      });
    }
    
    // Main processing loop
    let logAPIKeyBIEvent = true;
    const cursorService = new CursorService(context);

    const interval = setInterval(async () => {
      try {
        const apiKey = getOpikApiKey();

        if (!apiKey) {
          showInitialApiKeyWarning(context);
          return;
        }
        
        const VSInstallationPath = getDefaultVSCodeUserDataPath(context);
        const numberOfCursorTracesLogged = await cursorService.processCursorTraces(apiKey, VSInstallationPath);

        console.log(`Number of Cursor traces logged: ${numberOfCursorTracesLogged}`);
        console.log('Finished loop');
      } catch (error) {
        captureException(error);
        console.error('Error in main processing loop:', error);
      }
    }, 15000);

    context.subscriptions.push({
      dispose: () => clearInterval(interval)
    });
  } catch (error) {
    captureException(error);
    console.error('Error during extension activation:', error);
  }
}

export function deactivate() {
  console.log('Opik extension deactivated');
}
