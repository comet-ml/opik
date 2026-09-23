import { BASE_API_URL } from "@/api/api";

/** The server name every client registers the Opik MCP server under. */
export const MCP_SERVER_NAME = "opik-mcp";

/**
 * Derived rather than hard-coded, so a deeplink built on a development
 * environment does not register the production server.
 */
export const getMcpServerUrl = (): string =>
  new URL(`${BASE_API_URL}/v1/mcp`, window.location.origin).toString();

/** The install route for a deployment with no hosted server. */
export const cliConfigureCommand = (client: string) =>
  `uvx opik mcp configure --ai-client ${client}`;
