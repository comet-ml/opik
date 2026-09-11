import * as vscode from 'vscode';
import { getOpikApiKey, showDebugChannel } from './utils';

export function updateStatusBar(context: vscode.ExtensionContext) {
    const statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100);
    context.subscriptions.push(statusBarItem);

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
        arguments: ['@ext:opik.opik'],
        title: 'Open Opik Settings'
      };
      statusBarItem.tooltip = 'Opik is active. Click to view settings.';
      statusBarItem.show();
    }
  }

export function showInitialApiKeyWarning(context: vscode.ExtensionContext) {
  if (!context.globalState.get<boolean>('hasShownInitialApiKeyWarning', false)) {
    vscode.window.showErrorMessage(
      'To log your chat sessions to Opik you need an API Key. Configure it in VS Code settings or ~/.opik.config file.',
      'Open Settings',
      'Show Debug Logs'
    ).then(selection => {
      if (selection === 'Open Settings') {
        vscode.commands.executeCommand('workbench.action.openSettings', 'opik.apiKey');
      } else if (selection === 'Show Debug Logs') {
        // Enable debug logs and show the output channel
        const config = vscode.workspace.getConfiguration();
        config.update('opik.enableDebugLogs', true, vscode.ConfigurationTarget.Global);
        showDebugChannel();
        vscode.window.showInformationMessage('Debug logging enabled. You can disable it in settings (opik.enableDebugLogs).');
      }
    });

    context.globalState.update('hasShownInitialApiKeyWarning', true);
  }
}
