import * as vscode from 'vscode';
import { Sentry } from '../sentry';

export class MCPService {
    private context: vscode.ExtensionContext;
    private readonly serverName = 'opik-mcp';

    constructor(context: vscode.ExtensionContext) {
        this.context = context;
    }

    /**
     * Register the Opik MCP server with Cursor
     */
    async registerServer(apiKey: string): Promise<boolean> {
        try {
            // Get configuration values
            const config = vscode.workspace.getConfiguration();
            const mcpEnabled = config.get<boolean>('opik.mcp.enabled', true);
            const apiUrl = config.get<string>('opik.apiUrl', 'https://www.comet.com/opik/api');
            const workspace = config.get<string>('opik.workspace', 'default');

            if (!mcpEnabled) {
                console.log('MCP server registration is disabled in settings');
                return false;
            }

            console.log('Registering Opik MCP server...');

            // Register the server using Cursor's MCP extension API
            await (vscode as any).cursor.mcp.registerServer({
                name: this.serverName,
                server: {
                    command: 'npx',
                    args: [
                        '-y',
                        'opik-mcp',
                        '--apiKey',
                        apiKey,
                        '--apiUrl',
                        apiUrl,
                        '--workspace',
                        workspace
                    ],
                    env: {}
                }
            });


            console.log('✅ Opik MCP server registered successfully');

            // Show success message to user
            vscode.window.showInformationMessage(
                'Opik MCP server registered! You can now use Opik context in your chats.'
            );

            return true;

        } catch (error) {
            Sentry.captureException(error);
            console.error('❌ Failed to register Opik MCP server:', error);

            // Show error to user but don't block extension
            vscode.window.showWarningMessage(
                'Failed to register Opik MCP server. Chat logging will still work normally.'
            );

            return false;
        }
    }

    /**
     * Unregister the Opik MCP server
     */
    async unregisterServer(): Promise<boolean> {
        try {
            console.log('Unregistering Opik MCP server...');

            await (vscode as any).cursor.mcp.unregisterServer(this.serverName);
            console.log('✅ Opik MCP server unregistered successfully');
            return true;

        } catch (error) {
            Sentry.captureException(error);
            console.error('❌ Failed to unregister Opik MCP server:', error);
            return false;
        }
    }

    /**
     * Re-register the server with a new API key
     */
    async reregisterServer(apiKey: string): Promise<boolean> {
        console.log('Re-registering Opik MCP server with new API key...');

        // First unregister the existing server
        await this.unregisterServer();

        // Then register with new API key
        return await this.registerServer(apiKey);
    }

}
