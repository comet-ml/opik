import { BASE_API_URL } from "@/api/api";

/** The server name every client registers the Opik MCP server under. */
export const MCP_SERVER_NAME = "opik-mcp";

/**
 * The hosted MCP server this deployment serves.
 *
 * Derived, never hard-coded: a deeplink built against a literal production URL
 * would quietly register the production server while someone is testing on a
 * development environment, and nothing about the result would look wrong.
 *
 * `BASE_API_URL` is a path on a deployed build and an absolute URL when the
 * frontend runs locally against a remote environment, so both are resolved
 * against the page's own origin.
 */
export const getMcpServerUrl = (): string =>
  new URL(`${BASE_API_URL}/v1/mcp`, window.location.origin).toString();

/** The CLI route, for a client the deployment cannot deeplink to. */
export const cliConfigureCommand = (client: string) =>
  `uvx opik mcp configure --ai-client ${client}`;
