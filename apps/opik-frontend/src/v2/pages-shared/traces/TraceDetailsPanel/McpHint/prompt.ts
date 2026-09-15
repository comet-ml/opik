import { MCP_SERVER_NAME } from "./serverUrl";

type PromptContext = {
  traceId: string;
  spanId?: string;
  projectName: string;
};

// Project and workspace names are user-controlled and land in a prompt a coding
// agent will act on, so a newline plus an imperative would read there as a new
// instruction. Collapse to a single line and cap the length.
const inlineValue = (value: string, maxLength = 120) =>
  value
    .replace(/["\\]/g, "'")
    // eslint-disable-next-line no-control-regex
    .replace(/[\s\u0000-\u001f\u007f]+/g, " ")
    .trim()
    .slice(0, maxLength);

// Worded the same as the `Debug this trace` payload the connected-user popover
// will carry, so the two do not drift apart.
const debugStep = ({ traceId, spanId, projectName }: PromptContext) => {
  const entity = spanId
    ? `span ${spanId} of trace ${traceId}`
    : `trace ${traceId}`;

  return `4. Then read Opik ${entity} in project "${inlineValue(
    projectName,
  )}" — the error and its spans — work out what caused it, and fix it in the code.`;
};

const DETECT_STEP =
  '1. Detect which coding agents are installed here and which one you are; ask me "only you, or all of them?" and wait.';

// A newly registered MCP server is not available in the session that registered
// it, and that restart is where people lose the thread. The agent owns it.
const RELOAD_STEP =
  "3. Reload your MCP servers and finish the browser sign-in if prompted. If the server only loads in a new session, say so, ask me to restart you, and repeat step 4 verbatim so I can paste it back.";

const SKILLS =
  "Install the Opik skills the same way: `npx skills add comet-ml/opik-skills -g -y -a <agent>`.";

const NO_SECRETS = "Never print secrets you find in config files.";

/** Registers through each client's own command, which keeps `uv` off this path. */
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
 * For deployments where the MCP server is a local stdio process. Names the
 * workspace but never the API key: the CLI reads that from the developer's own
 * configuration, and when it cannot, it fails rather than prompting, so the
 * prompt has to tell the agent what to do about it.
 */
export const buildLocalInstallPrompt = (
  context: PromptContext & { workspaceName: string },
): string =>
  [
    "Connect me to Opik MCP, then debug a failing trace.",
    "",
    DETECT_STEP,
    `2. Install uv if it is missing, then run \`uvx opik mcp configure --ai-client <agent> --skills\` for each chosen agent. Naming the client is what lets it run without a terminal. It reuses my existing Opik configuration; if it reports that Opik is not configured yet, you cannot answer that for me — ask me for my API key, then re-run with OPIK_API_KEY set and OPIK_WORKSPACE="${inlineValue(
      context.workspaceName,
    )}" in that command's environment. Do not guess the key. ${NO_SECRETS}`,
    RELOAD_STEP,
    debugStep(context),
  ].join("\n");
