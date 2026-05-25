# OpenClaw is configured through its CLI, not a Python package.
# Full docs: https://github.com/comet-ml/opik-openclaw

# 1. Install the Opik plugin into your OpenClaw Gateway:
openclaw plugins install clawhub:@opik/opik-openclaw

# 2. Run the interactive configuration wizard. It validates your
# endpoint and credentials, then writes config under
# plugins.entries.opik-openclaw:
openclaw opik configure

# 3. Verify the effective configuration:
openclaw opik status

# 4. Start the gateway and send a test message. Traces will stream
# into the "PROJECT_NAME_PLACEHOLDER" Opik project:
openclaw gateway run
openclaw message send "hello from openclaw"

# The wizard writes a config block like this; you can also edit it by hand:
#
# {
#   "plugins": {
#     "entries": {
#       "opik-openclaw": {
#         "enabled": true,
#         "hooks": { "allowConversationAccess": true },
#         "config": {
#           "enabled": true,
#           "apiKey": "your-api-key",
#           "apiUrl": "https://www.comet.com/opik/api",
#           "projectName": "PROJECT_NAME_PLACEHOLDER",
#           "workspaceName": "default"
#         }
#       }
#     }
#   }
# }
