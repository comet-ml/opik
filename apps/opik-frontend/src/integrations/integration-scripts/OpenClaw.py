# OpenClaw is configured via its CLI, not a Python package.
# See https://github.com/comet-ml/opik-openclaw for full details.

# 1. Install the Opik plugin into OpenClaw:
#    openclaw plugins install clawhub:@opik/opik-openclaw

# 2. Run the interactive configuration wizard (sets your API key,
#    workspace, and project):
#    openclaw opik configure

# 3. Verify the effective configuration:
#    openclaw opik status

# 4. Start the OpenClaw gateway. Agent traces, sub-agent spans, and
#    tool calls will stream into your Opik project:
#    openclaw gateway run

# Alternatively, configure via environment variables:
#   OPIK_API_KEY=...
#   OPIK_WORKSPACE=...
#   OPIK_PROJECT_NAME=PROJECT_NAME_PLACEHOLDER
#   OPIK_URL_OVERRIDE=https://www.comet.com/opik/api
