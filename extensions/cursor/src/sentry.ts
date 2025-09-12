import * as Sentry from "@sentry/node";
import * as vscode from 'vscode';

export function initializeSentry(context: vscode.ExtensionContext) {
  const sentryDsn = "https://68bfd76b1b03ebc98b047f769ef9eebb@o168229.ingest.us.sentry.io/451000128274432";
  
  Sentry.init({
    dsn: sentryDsn,
    
    // Send structured logs to Sentry
    enableLogs: true,
    
    // Set the environment based on extension mode
    environment: context.extensionMode === vscode.ExtensionMode.Development ? 'development' : 'production',
    
    // Add extension-specific context
    initialScope: {
      tags: {
        component: 'vscode-extension',
        extension: 'opik-cursor'
      },
      contexts: {
        extension: {
          name: 'opik',
          version: context.extension.packageJSON.version
        }
      }
    },

    integrations: [
      // Send console.warn and console.error calls as logs to Sentry
      Sentry.consoleLoggingIntegration({ 
        levels: ["warn", "error"] // Only capture warnings and errors from console
      }),
    ],

    // Disable performance monitoring since we don't need it
    tracesSampleRate: 0
  });

  // Add VS Code context to all events
  Sentry.setContext("vscode", {
    version: vscode.version,
    workspace: vscode.workspace.name || 'unknown'
  });

  console.log("Sentry initialized for error tracking");
}

// Helper function to capture exceptions with context
export function captureExceptionWithContext(error: Error, context?: Record<string, any>) {
  Sentry.withScope((scope) => {
    if (context) {
      Object.entries(context).forEach(([key, value]) => {
        scope.setTag(key, String(value));
      });
    }
    Sentry.captureException(error);
  });
}

// Get the Sentry logger
export const { logger } = Sentry;
