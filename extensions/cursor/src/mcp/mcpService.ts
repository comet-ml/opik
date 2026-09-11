import * as vscode from 'vscode';
import { captureException } from '../sentry';
import { getOpikApiKey } from '../utils';

export class MCPService {
    private context: vscode.ExtensionContext;
    private readonly serverName = 'opik-mcp';

    constructor(context: vscode.ExtensionContext) {
        this.context = context;
    }

    /**
     * Register the Opik MCP server with Cursor
     */
    async registerServer(): Promise<boolean> {
        try {
            // Get configuration values
            const config = vscode.workspace.getConfiguration();
            const mcpEnabled = config.get<boolean>('opik.mcp.enabled', true);
            const apiUrl = config.get<string>('opik.apiUrl', 'https://www.comet.com/opik/api');
            const workspace = config.get<string>('opik.workspace', 'default');
            const projectName = config.get<string>('opik.projectName', 'cursor');

            const apiKey = getOpikApiKey();
            if (!mcpEnabled) {
                console.log('MCP server registration is disabled in settings');
                return false;
            }

            // Register the server using Cursor's MCP extension API
            await (vscode as any).cursor.mcp.unregisterServer(this.serverName);

            await (vscode as any).cursor.mcp.registerServer({
                name: this.serverName,
                server: {
                    command: 'npx',
                    args: [
                        '-y',
                        'opik-mcp',
                        ...(apiKey ? ['--apiKey', apiKey] : []),
                        '--apiUrl',
                        apiUrl,
                        '--workspace',
                        workspace,
                        '--projectName',
                        projectName
                    ],
                    env: {}
                }
            });

            return true;

        } catch (error) {
            captureException(error);
            console.error('❌ Failed to register Opik MCP server:', error);

            // Show error to user but don't block extension
            vscode.window.showWarningMessage(
                'Failed to register Opik MCP server. Chat logging will still work normally.'
            );

            return false;
        }
    }


}
