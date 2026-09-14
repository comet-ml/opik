import { MCP_SERVER_NAME } from "./serverUrl";

type PromptContext = {
  traceId: string;
  projectName: string;
};

// Step 4 is deliberately worded the same as the `Debug this trace` payload the
// connected-user popover will carry, so the two never drift apart.
const debugStep = ({ traceId, projectName }: PromptContext) =>
  `4. Then read Opik trace ${traceId} in project "${projectName}" — the error and its spans — work out what caused it, and fix it in the code.`;

const DETECT_STEP =
  '1. Detect which coding agents are installed here and which one you are; ask me "only you, or all of them?" and wait.';

// The hand-off, and the reason this prompt exists in this shape: a newly
// registered MCP server is not available in the session that registered it, and
// that restart is exactly where people lose the thread. The agent owns it.
const RELOAD_STEP =
  "3. Reload your MCP servers and finish the browser sign-in if prompted. If the server only loads in a new session, say so, ask me to restart you, and repeat step 4 verbatim so I can paste it back.";

const SKILLS =
  "Install the Opik skills the same way: `npx skills add comet-ml/opik-skills -g -y -a <agent>`.";

const NO_SECRETS = "Never print secrets you find in config files.";

/**
 * For deployments with a hosted MCP server.
 *
 * Step 2 registers through each client's own command, which is what keeps `uv`
 * off this path entirely — the one prerequisite a developer is most likely to
 * be missing.
 */
export const buildHostedInstallPrompt = (
  context: PromptContext & { serverUrl: string },
): string =>
  [
    "Connect me to Opik MCP, then debug a failing trace.",
    "",
    DETECT_STEP,
    `2. For each chosen agent, add the MCP server \`${MCP_SERVER_NAME}\` (Streamable HTTP, ${context.serverUrl}, no credentials) to its user-level config, using that client's own command where it has one (\`claude mcp add --transport http --scope user ...\`, \`codex mcp add ... --url ...\`), otherwise \`npx add-mcp <url> --name ${MCP_SERVER_NAME} -g -a <agent>\`. Skip an agent that already has a server with that URL. ${SKILLS} ${NO_SECRETS}`,
    RELOAD_STEP,
    debugStep(context),
  ].join("\n");

/**
 * For self-hosted and local deployments, where the MCP server is a local stdio
 * process rather than something a client can be pointed at.
 *
 * Names the workspace but never the API key: the CLI reads the key from the
 * developer's own Opik configuration, or asks for it. A prompt on the clipboard
 * is not a place to put credentials.
 */
export const buildLocalInstallPrompt = (
  context: PromptContext & { workspaceName: string },
): string =>
  [
    "Connect me to Opik MCP, then debug a failing trace.",
    "",
    DETECT_STEP,
    `2. Install uv if it is missing, then run \`uvx opik mcp configure --ai-client <agent> --skills\` for each chosen agent, against workspace "${context.workspaceName}". If it asks for an API key, ask me for it — do not guess. ${NO_SECRETS}`,
    RELOAD_STEP,
    debugStep(context),
  ].join("\n");
