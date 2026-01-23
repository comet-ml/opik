#!/usr/bin/env bash
# Convert .agents/mcp.json (Cursor format) to .mcp.json (Claude CLI format)
# Usage: convert-mcp.sh <input-file> <output-file>
#
# Transformations:
# 1. Replace ${workspaceFolder}/ with empty string in all paths
# 2. For docker commands with envFile:
#    - Add --env-file <path> to args (after --rm if present, otherwise prepend)
#    - Remove -e VARNAME pairs (where VARNAME has no =)
#    - Remove the envFile property
# 3. For non-docker commands with envFile:
#    - Parse the env file and merge into .env map
#    - Remove the envFile property

set -euo pipefail

input="${1:-}"
output="${2:-}"

if [[ -z "$input" || -z "$output" ]]; then
    echo "Usage: convert-mcp.sh <input-file> <output-file>" >&2
    exit 1
fi

if [[ ! -f "$input" ]]; then
    echo "Error: Input file '$input' does not exist" >&2
    exit 1
fi

if ! command -v jq &> /dev/null; then
    echo "Error: jq is required but not installed" >&2
    exit 1
fi

# Validate input is valid JSON
if ! jq empty "$input" 2>/dev/null; then
    echo "Error: Input file '$input' is not valid JSON" >&2
    exit 1
fi

# Helper function to parse env file into JSON object
# Handles: missing files, empty files, comment-only files, special characters
parse_env_file() {
    local file="$1"
    if [[ ! -f "$file" ]]; then
        echo "{}"
        return
    fi

    # Use jq to properly escape values for JSON safety
    local pairs=()
    while IFS= read -r line; do
        # Skip empty lines and comments
        [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
        # Must contain =
        [[ "$line" != *"="* ]] && continue

        # Split on first = only
        key="${line%%=*}"
        value="${line#*=}"

        # Trim whitespace from key
        key="${key#"${key%%[![:space:]]*}"}"
        key="${key%"${key##*[![:space:]]}"}"

        # Skip if key is empty
        [[ -z "$key" ]] && continue

        # Clean ${workspaceFolder}/ from value
        value="${value//\$\{workspaceFolder\}\//}"

        # Use jq to safely escape both key and value
        pairs+=("$(jq -n --arg k "$key" --arg v "$value" '{($k): $v}')")
    done < "$file"

    # Merge all pairs into single object
    if [[ ${#pairs[@]} -eq 0 ]]; then
        echo "{}"
    else
        printf '%s\n' "${pairs[@]}" | jq -s 'add // {}'
    fi
}

# Pre-parse env files for non-docker commands
# Build a JSON object mapping cleaned envFile path -> parsed env vars
env_data="{}"

# Check if mcpServers exists and has entries
if jq -e '.mcpServers // empty | keys | length > 0' "$input" >/dev/null 2>&1; then
    while IFS= read -r env_path; do
        [[ -z "$env_path" ]] && continue
        parsed=$(parse_env_file "$env_path")
        [[ "$parsed" == "{}" ]] && continue
        env_data=$(echo "$env_data" | jq --arg path "$env_path" --argjson vars "$parsed" '. + {($path): $vars}')
    done < <(jq -r '
        .mcpServers // {} | to_entries[] |
        select(.value.envFile and .value.command != "docker") |
        .value.envFile |
        gsub("\\$\\{workspaceFolder\\}/"; "")
    ' "$input" 2>/dev/null | sort -u)
fi

jq --argjson envData "$env_data" '
# Helper: clean ${workspaceFolder}/ from paths
def clean_path:
  if type == "string" then gsub("\\$\\{workspaceFolder\\}/"; "") else . end;

# Helper: safely get array, defaulting null/non-array to []
def safe_array:
  if type == "array" then . else [] end;

# Helper: safely get object, defaulting null/non-object to {}
def safe_object:
  if type == "object" then . else {} end;

# Helper: remove -e VARNAME pairs where VARNAME has no = (env vars provided by env-file)
def remove_standalone_env_vars:
  safe_array |
  . as $arr |
  reduce range(0; $arr | length) as $i (
    {result: [], skip: false};
    if .skip then
      .skip = false
    elif ($arr[$i] == "-e") and ($i + 1 < ($arr | length)) and (($arr[$i + 1] // "") | tostring | contains("=") | not) then
      .skip = true
    else
      .result += [$arr[$i]]
    end
  ) | .result;

# Helper: insert --env-file into args
# Tries to insert after --rm; if --rm not found, prepends to args
def insert_env_file($path):
  safe_array |
  if any(. == "--rm") then
    # Insert after --rm
    reduce .[] as $item (
      [];
      . + [$item] + (if $item == "--rm" then ["--env-file", $path] else [] end)
    )
  else
    # Prepend --env-file to args
    ["--env-file", $path] + .
  end;

# Main transformation - handle missing/null mcpServers gracefully
if .mcpServers | type == "object" then
  .mcpServers |= with_entries(
    # Skip null server entries
    if .value | type != "object" then .
    else
      .value |= (
        # Get cleaned envFile path if present and non-empty
        (if (.envFile // "") | length > 0 then (.envFile | clean_path) else null end) as $envPath |

        # Transform based on command type
        if .command == "docker" and $envPath then
          # Docker: move envFile to --env-file arg, remove standalone -e vars
          .args = (.args | remove_standalone_env_vars | insert_env_file($envPath)) |
          del(.envFile)
        elif $envPath then
          # Non-docker: parse envFile and merge into .env map
          # (Claude CLI only expands envFile for docker commands)
          ($envData[$envPath] // {}) as $parsedEnv |
          .env = ((.env | safe_object) + $parsedEnv) |
          del(.envFile)
        else
          .
        end |

        # Clean ${workspaceFolder}/ from cwd if present
        if .cwd then .cwd |= clean_path else . end |

        # Clean ${workspaceFolder}/ from env values if present
        if .env | type == "object" then
          .env |= with_entries(
            if .value | type == "string" then .value |= clean_path else . end
          )
        else . end
      )
    end
  )
else
  .
end
' "$input" > "$output"

echo "  Converted MCP config: $input -> $output"
