#!/usr/bin/env bash
# Convert .agents/mcp.json (Cursor format) to .mcp.json (Claude CLI format)
# Usage: convert-mcp.sh <input-file> <output-file>
#
# Transformations:
# 1. Replace ${workspaceFolder}/ with empty string in all paths
# 2. For docker commands with envFile:
#    - Add --env-file <path> to args after --rm
#    - Remove -e VARNAME pairs (where VARNAME has no =)
#    - Remove the envFile property
# 3. For non-docker commands with envFile:
#    - Just clean the path

set -euo pipefail

input="${1:-}"
output="${2:-}"

if [[ -z "$input" || -z "$output" ]]; then
    echo "Usage: convert-mcp.sh <input-file> <output-file>" >&2
    exit 1
fi

if ! command -v jq &> /dev/null; then
    echo "Error: jq is required but not installed" >&2
    exit 1
fi

jq '
# Helper: clean ${workspaceFolder}/ from paths
def clean_path:
  if type == "string" then gsub("\\$\\{workspaceFolder\\}/"; "") else . end;

# Helper: remove -e VARNAME pairs where VARNAME has no = (env vars provided by env-file)
def remove_standalone_env_vars:
  . as $arr |
  reduce range(0; $arr | length) as $i (
    {result: [], skip: false};
    if .skip then
      .skip = false
    elif $arr[$i] == "-e" and ($i + 1 < ($arr | length)) and ($arr[$i + 1] | contains("=") | not) then
      .skip = true
    else
      .result += [$arr[$i]]
    end
  ) | .result;

# Helper: insert --env-file after --rm in args
def insert_env_file($path):
  reduce .[] as $item (
    [];
    . + [$item] + (if $item == "--rm" then ["--env-file", $path] else [] end)
  );

# Main transformation
.mcpServers |= with_entries(
  .value |= (
    # Get cleaned envFile path if present
    (if .envFile then (.envFile | clean_path) else null end) as $envPath |

    # Transform based on command type
    if .command == "docker" and $envPath then
      # Docker: move envFile to --env-file arg, remove standalone -e vars
      .args = (.args | remove_standalone_env_vars | insert_env_file($envPath)) |
      del(.envFile)
    elif $envPath then
      # Non-docker: just clean the envFile path
      .envFile = $envPath
    else
      .
    end |

    # Clean ${workspaceFolder}/ from cwd
    if .cwd then .cwd |= clean_path else . end |

    # Clean ${workspaceFolder}/ from env values
    if .env then .env |= with_entries(.value |= clean_path) else . end
  )
)
' "$input" > "$output"

echo "  Converted MCP config: $input -> $output"
