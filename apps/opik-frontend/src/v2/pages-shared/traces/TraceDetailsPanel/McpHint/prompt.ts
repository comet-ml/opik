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
//
// The verification is not ceremony: a failed write, a pending sign-in and a
// stale connection all look like success from the outside, and step 4 would
// then report that it cannot see the trace rather than that nothing installed.
const RELOAD_STEP =
  "3. Reload your MCP servers and finish the browser sign-in if prompted. Then verify, per agent, by calling the Opik `list` tool with entity_type project and showing me the result; report each agent as verified or pending, and do not go on from an agent that is neither. If the server only loads in a new session, say so, ask me to restart you, and repeat step 4 verbatim so I can paste it back.";

const SKILLS =
  "Install the Opik skills the same way: `npx skills add comet-ml/opik-skills -g -y -a <agent>`.";

const NO_SECRETS = "Never print secrets you find in config files.";

/**
 * The hosted server needs no credentials, so the prompt can hand over the URL
 * and leave the how to the agent. Enumerating each client's install command
 * only dated the prompt: the agent knows its own config, and a client we never
 * listed is the common case.
 */
export const buildHostedInstallPrompt = (
  context: PromptContext & { serverUrl: string },
): string =>
  [
    "Connect me to Opik MCP, then debug a failing trace.",
    "",
    DETECT_STEP,
    `2. For each chosen agent, register the MCP server \`${MCP_SERVER_NAME}\` with it: Streamable HTTP at ${context.serverUrl}, no credentials. Use that client's own way in — its CLI where it has one, otherwise its user-level config. Skip an agent that already has a server with that URL. ${SKILLS} ${NO_SECRETS}`,
    RELOAD_STEP,
    debugStep(context),
  ].join("\n");

/**
 * A local server is a stdio process holding an API key, so there is no URL to
 * hand over and `opik mcp configure` is the only way in. It runs unattended
 * once a client is named, but only while an Opik configuration already exists;
 * without one it needs a terminal, and that is mine, not the agent's.
 */
export const buildLocalInstallPrompt = (
  context: PromptContext & { workspaceName: string },
): string =>
  [
    "Connect me to Opik MCP, then debug a failing trace.",
    "",
    DETECT_STEP,
    `2. Install uv if it is missing, then run \`uvx opik mcp configure --ai-client <agent> --skills\` for each chosen agent. It reuses my existing Opik configuration, which may point somewhere else: the workspace I am looking at is "${inlineValue(
      context.workspaceName,
    )}", so check the one it configured and stop and tell me if they differ rather than debugging the wrong workspace. If it needs a terminal, or asks for anything you cannot answer, stop and ask me to run it myself, then carry on from step 3. ${NO_SECRETS}`,
    RELOAD_STEP,
    debugStep(context),
  ].join("\n");
