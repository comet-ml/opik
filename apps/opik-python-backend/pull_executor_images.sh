#!/bin/sh
# Using sh as bash is not available in the Alpine image
set -e

PYTHON_CODE_EXECUTOR_ASSET_REPOSITORY="${PYTHON_CODE_EXECUTOR_ASSET_REPOSITORY:-comet-ml/opik}"
PYTHON_CODE_EXECUTOR_ASSET_NAME="${PYTHON_CODE_EXECUTOR_ASSET_NAME:-opik-sandbox-executor-python_image.tar.gz}"
# TODO: temporarily hardcoding to 1.4.12, as the latest tag is not available as release asset yet
PYTHON_CODE_EXECUTOR_ASSERT_TAG="1.4.12"

IMAGES_DIR="./images"

# This script leverages the GitHub API:
# - Releases: https://docs.github.com/en/rest/releases/releases
# - Assets: https://docs.github.com/en/rest/releases/assets

echo "Cleaning up and creating the images directory"

rm -rf "${IMAGES_DIR}"
mkdir "${IMAGES_DIR}"

echo "Successfully cleaned up and created the images directory"

echo "Getting the release from repository: '${PYTHON_CODE_EXECUTOR_ASSET_REPOSITORY}', tag: '${PYTHON_CODE_EXECUTOR_ASSERT_TAG}'"

# Getting the release by tag name:
# - https://docs.github.com/en/rest/releases/releases?apiVersion=2022-11-28#get-a-release-by-tag-name
RELEASE_RESPONSE=$(
  curl -L \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "https://api.github.com/repos/${PYTHON_CODE_EXECUTOR_ASSET_REPOSITORY}/releases/tags/${PYTHON_CODE_EXECUTOR_ASSERT_TAG}"
)

echo "Successfully retrieved release response"

echo "Parsing the asset ID for asset name: '${PYTHON_CODE_EXECUTOR_ASSET_NAME}'"

# The tr command removes invalid characters from the response: from unicode U+0000 to U+001F.
# The jq command parses the JSON response with -r flag to get output raw strings, not JSON texts.
ASSET_ID=$(
  echo "${RELEASE_RESPONSE}" | \
  tr -d '\000-\037' | \
  jq -r --arg NAME "${PYTHON_CODE_EXECUTOR_ASSET_NAME}" '.assets[] | select(.name == $NAME) | .id'
)

echo "Successfully parsed asset ID: '${ASSET_ID}'"

echo "Downloading from repository: '${PYTHON_CODE_EXECUTOR_ASSET_REPOSITORY}', asset ID: '${ASSET_ID}'"

# Getting the release asset:
# - https://docs.github.com/en/rest/releases/assets?apiVersion=2022-11-28#get-a-release-asset
# Writing the output to a file.
curl -L \
  -H "Accept: application/octet-stream" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "https://api.github.com/repos/${PYTHON_CODE_EXECUTOR_ASSET_REPOSITORY}/releases/assets/${ASSET_ID}" \
  -o "${IMAGES_DIR}"/"${PYTHON_CODE_EXECUTOR_ASSET_NAME}"

echo "Successfully downloaded from repository file: '${IMAGES_DIR}/${PYTHON_CODE_EXECUTOR_ASSET_NAME}'"
