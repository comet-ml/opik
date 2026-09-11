#!/usr/bin/env bash
#
# Analyze a Trivy vulnerability scan report.
#
# Usage:
#   analyze_trivy_report.sh <report_file> <image_url> [--workflow-url <url>] [--artifact-name <name>]
#
# Outputs (written to GITHUB_OUTPUT if set, otherwise printed to stdout):
#   has-base-image-vulns=true|false
#   has-app-vulns=true|false
#   base-image-title=...
#   base-image-message=...
#   app-title=...
#   app-message=...

set -euo pipefail

usage() {
  echo "Usage: $0 <report_file> <image_url> [options]"
  echo ""
  echo "Options:"
  echo "  --workflow-url <url>      URL to the workflow run"
  echo "  --artifact-name <name>    Name of the artifact containing the full report"
  exit 1
}

if [ $# -lt 2 ]; then
  usage
fi

REPORT_FILE="$1"
IMAGE_URL="$2"
shift 2

WORKFLOW_URL=""
ARTIFACT_NAME=""

while [ $# -gt 0 ]; do
  case "$1" in
    --workflow-url) WORKFLOW_URL="$2"; shift 2 ;;
    --artifact-name) ARTIFACT_NAME="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

OUTPUT_FILE="${GITHUB_OUTPUT:-}"
IMAGE_NAME=$(basename "${IMAGE_URL}" | cut -d: -f1)
IMAGE_VERSION=$(echo "${IMAGE_URL}" | grep -oP ':\K[^:]+$' || echo "latest")

# Helper to write outputs
write_output() {
  if [ -n "$OUTPUT_FILE" ]; then
    echo "$1" >> "$OUTPUT_FILE"
  else
    echo "$1"
  fi
}

write_multiline_output() {
  local key="$1"
  local value="$2"
  if [ -n "$OUTPUT_FILE" ]; then
    {
      echo "${key}<<EOF"
      echo "$value"
      echo "EOF"
    } >> "$OUTPUT_FILE"
  else
    echo "${key}=${value}"
  fi
}

HAS_BASE_IMAGE_VULNS=false
HAS_APP_VULNS=false
BASE_TITLE=""
BASE_MSG=""
APP_TITLE=""
APP_MSG=""

if [ -f "$REPORT_FILE" ]; then
  TOTAL_LINE=$(grep -E "^Total:" "$REPORT_FILE" | head -1 || echo "")

  if [ -n "$TOTAL_LINE" ]; then
    TOTAL=$(echo "$TOTAL_LINE" | grep -oP 'Total: \K[0-9]+' || echo "0")
    CRITICAL=$(echo "$TOTAL_LINE" | grep -oP 'CRITICAL: \K[0-9]+' || echo "0")
    HIGH=$(echo "$TOTAL_LINE" | grep -oP 'HIGH: \K[0-9]+' || echo "0")

    if [ "$TOTAL" != "0" ]; then
      echo "Found vulnerabilities - Total: $TOTAL (CRITICAL: $CRITICAL, HIGH: $HIGH)"

      SEVERITY_SUMMARY="Total: *${TOTAL}* (CRITICAL: *${CRITICAL}*, HIGH: *${HIGH}*)"

      ARTIFACT_MSG=""
      if [ -n "$WORKFLOW_URL" ] && [ -n "$ARTIFACT_NAME" ]; then
        ARTIFACT_MSG=$'\n*Full Report:* Download artifact `'"${ARTIFACT_NAME}"'` from <'"${WORKFLOW_URL}"'|workflow run>'
      fi

      if grep -qiE "^\s*(amazon|alpine|debian|ubuntu|centos|rhel|fedora|rocky|oracle|mariner|wolfi|chainguard|busybox|distroless)" "$REPORT_FILE"; then
        if awk '/^(amazon|alpine|debian|ubuntu|centos|rhel|fedora|rocky|oracle|mariner|wolfi|chainguard)/i,/^$/' "$REPORT_FILE" | grep -qE "CVE-|GHSA-"; then
          HAS_BASE_IMAGE_VULNS=true
        fi
      fi

      if grep -qiE "\.(jar|war|ear|js|py|rb|go|mod|lock|toml|gemspec)" "$REPORT_FILE"; then
        if grep -qE "CVE-|GHSA-" "$REPORT_FILE"; then
          HAS_APP_VULNS=true
        fi
      fi

      if [ "$HAS_BASE_IMAGE_VULNS" = "false" ] && [ "$HAS_APP_VULNS" = "false" ]; then
        if grep -qE "CVE-|GHSA-" "$REPORT_FILE"; then
          HAS_APP_VULNS=true
        fi
      fi

      if [ "$HAS_BASE_IMAGE_VULNS" = "true" ]; then
        BASE_TITLE="Trivy Scan: Base Image Vulnerabilities Found in ${IMAGE_NAME}:${IMAGE_VERSION}"
        BASE_MSG="*Image:* \`${IMAGE_URL}\`

*Vulnerabilities found in the base image:*
${SEVERITY_SUMMARY}

:warning: The vulnerabilities are in the *base image* (OS packages).

To fix this, rebuild the image with an updated base image.${ARTIFACT_MSG}"
      fi

      if [ "$HAS_APP_VULNS" = "true" ]; then
        APP_TITLE="Trivy Scan: Application Vulnerabilities Found in ${IMAGE_NAME}:${IMAGE_VERSION}"
        APP_MSG="*Image:* \`${IMAGE_URL}\`

*Vulnerabilities found in application dependencies (jars/libraries):*
${SEVERITY_SUMMARY}

:mag: The vulnerabilities are in *application dependencies* (jars, libraries, packages).

*Action required:* update the affected dependencies to resolve these vulnerabilities.${ARTIFACT_MSG}"
      fi
    else
      echo "No vulnerabilities found"
    fi
  else
    echo "No vulnerability summary line found (image may be clean or scan failed)"
  fi
else
  echo "Report file not found, skipping analysis"
fi

# Write all outputs once
write_output "has-base-image-vulns=${HAS_BASE_IMAGE_VULNS}"
write_output "has-app-vulns=${HAS_APP_VULNS}"
write_output "base-image-title=${BASE_TITLE}"
write_output "app-title=${APP_TITLE}"

if [ -n "$BASE_MSG" ]; then
  write_multiline_output "base-image-message" "$BASE_MSG"
else
  write_output "base-image-message="
fi

if [ -n "$APP_MSG" ]; then
  write_multiline_output "app-message" "$APP_MSG"
else
  write_output "app-message="
fi
