import { NodeClient, Scope, defaultStackParser, makeNodeTransport } from "@sentry/node";
import * as vscode from 'vscode';
import * as os from 'os';

let isolatedScope: Scope | null = null;

export function initializeSentry(context: vscode.ExtensionContext, userId?: string) {
  const sentryDsn = "https://68bfd76b1b03ebc98b047f769ef9eebb@o168229.ingest.us.sentry.io/4510001282744321";

  // Create isolated client without global integrations
  const client = new NodeClient({
    dsn: sentryDsn,
    transport: makeNodeTransport,
    stackParser: defaultStackParser,

    // Send structured logs to Sentry
    enableLogs: true,

    // Set the environment based on extension mode
    environment: context.extensionMode === vscode.ExtensionMode.Development ? 'development' : 'production',

    // Only include safe integrations that don't use global state
    integrations: [],

    // Disable performance monitoring since we don't need it
    tracesSampleRate: 0
  });

  // Create isolated scope for this extension
  isolatedScope = new Scope();
  isolatedScope.setClient(client);

  // Set extension-specific context
  isolatedScope.setTags({
    component: 'vscode-extension',
    extension: 'opik-cursor'
  });

  isolatedScope.setContext('extension', {
    name: 'opik',
    version: context.extension.packageJSON.version
  });

  isolatedScope.setContext('vscode', {
    version: vscode.version,
    workspace: vscode.workspace.name || 'unknown'
  });

  // Set user context if userId is provided
  if (userId) {
    try {
      const userInfo = os.userInfo();
      isolatedScope.setUser({
        id: userId,
        username: userInfo.username
      });
    } catch (error) {
      // Fallback if os.userInfo() fails
      isolatedScope.setUser({
        id: userId
      });
    }
  }

  client.init();

  console.log("Sentry initialized for error tracking (isolated mode)");
}

// Isolated error capture functions that only capture errors from this extension
export function captureException(error: any) {
  if (isolatedScope) {
    isolatedScope.captureException(error);
  }
}

export function captureMessage(message: string, level: 'fatal' | 'error' | 'warning' | 'log' | 'info' | 'debug' = 'info') {
  if (isolatedScope) {
    isolatedScope.captureMessage(message, level);
  }
}
