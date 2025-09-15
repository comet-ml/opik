import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';
import { getDefaultVSCodeUserDataPath, getOrCreateUUID, getOpikApiKey } from './utils';
import { CursorService } from './cursor/cursorService';
import { initializeSentry, Sentry } from './sentry';


export function activate(context: vscode.ExtensionContext) {
  // Initialize Sentry first
  initializeSentry(context);
  
  try {
    console.log('Opik extension is now active!');
    // Create status bar item for API key configuration
    const statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    context.subscriptions.push(statusBarItem);

    // Function to update status bar based on API key configuration
    function updateStatusBar() {
      const apiKey = getOpikApiKey();
      
      if (!apiKey) {
        statusBarItem.text = "$(warning) Opik: Configure API Key";
        statusBarItem.backgroundColor = new vscode.ThemeColor('statusBarItem.warningBackground');
        statusBarItem.command = {
          command: 'workbench.action.openSettings',
          arguments: ['opik.apiKey'],
          title: 'Configure Opik API Key'
        };
        statusBarItem.tooltip = 'Click to configure Opik API Key in VS Code settings, or use ~/.opik.config file';
        statusBarItem.show();
      } else {
        statusBarItem.text = "$(check) Opik: Active";
        statusBarItem.backgroundColor = undefined;
        statusBarItem.command = {
          command: 'workbench.action.openSettings',
          arguments: ['@ext:jverre.opik'],
          title: 'Open Opik Settings'
        };
        statusBarItem.tooltip = 'Opik is active. Click to view settings.';
        statusBarItem.show();
      }
    }

    // Initial status bar update
    updateStatusBar();

    // Listen for configuration changes
    const configChangeListener = vscode.workspace.onDidChangeConfiguration(event => {
      if (event.affectsConfiguration('opik.apiKey')) {
        updateStatusBar();
      }
    });
    context.subscriptions.push(configChangeListener);

    // Watch for changes to ~/.opik.config file
    try {
      const opikConfigPath = path.join(os.homedir(), '.opik.config');
      if (fs.existsSync(opikConfigPath)) {
        const configWatcher = fs.watch(opikConfigPath, (eventType) => {
          if (eventType === 'change') {
            updateStatusBar();
          }
        });
        context.subscriptions.push({
          dispose: () => configWatcher.close()
        });
      }
    } catch (error: any) {
      // File watching is optional, don't fail if it doesn't work
      console.log('Could not watch ~/.opik.config file:', error);
    }

    // Log configuration info for debugging
    const config = vscode.workspace.getConfiguration();
    const apiKey = getOpikApiKey();
    const cursorProjectName: string = config.get('opik.projectName') || 'cursor';
    const customVSCodePath: string = config.get('opik.VSCodePath') || '';
    

    const userDataPath = customVSCodePath || '';
    let VSInstallationPath = '';
    try {
      VSInstallationPath = fs.existsSync(userDataPath) ? userDataPath : getDefaultVSCodeUserDataPath(context);
    } catch (error) {
      Sentry.captureException(error);
      vscode.window.showErrorMessage('Failed to get VSCode user data path. Please check your configuration.');
      return;
    }


    let showAPIKeyWarning = true;
    let logAPIKeyBIEvent = true;
    const cursorService = new CursorService(context);

    const interval = setInterval(async () => {
      try {
        const apiKey = getOpikApiKey();

        if (!apiKey && showAPIKeyWarning) {
          // Track API key configuration issues
          const error = new Error("No API key configured - cannot log traces to Opik");
          Sentry.captureException(error);
          
          vscode.window.showErrorMessage(
            'To log your chat sessions to Opik you need an API Key. Configure it in VS Code settings or ~/.opik.config file.',
            'Open Settings'
          ).then(selection => {
            if (selection === 'Open Settings') {
              vscode.commands.executeCommand('workbench.action.openSettings', 'opik.apiKey');
            }
          });

          showAPIKeyWarning = false;
          return;
        } else if (!apiKey) {
          return;
        } else if (apiKey && logAPIKeyBIEvent) {
          vscode.window.showInformationMessage(
            'Your chat history will now be logged to Opik!'
          )
          logAPIKeyBIEvent = false;
        }

        const numberOfCursorTracesLogged = await cursorService.processCursorTraces(apiKey, VSInstallationPath);
        
        console.log(`Number of Cursor traces logged: ${numberOfCursorTracesLogged}`);
        console.log('Finished loop');
      } catch (error) {
        Sentry.captureException(error);
        console.error('Error in main processing loop:', error);
      }
    }, 5000);

    context.subscriptions.push({
      dispose: () => clearInterval(interval)
    });
  } catch (error) {
    Sentry.captureException(error);
    console.error('Error during extension activation:', error);
  }
}

export function deactivate() { 
  console.log('Opik extension deactivated');
}
