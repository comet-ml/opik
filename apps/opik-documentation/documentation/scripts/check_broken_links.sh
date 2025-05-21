#!/bin/bash

# Configuration
PORT=3334
DOCS_URL="http://localhost:$PORT/docs/opik"
MAX_RETRIES=3
RETRY_INTERVAL=2

# List of domains to exclude from link checking
EXCLUDED_DOMAINS=("cloud.ibm.com" "https://www.kaggle.com/whitepaper-prompt-engineering" "https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img")

# Cleanup function to ensure the background server is always terminated
cleanup() {
  if [ ! -z "$SERVER_PID" ]; then
    kill -INT $SERVER_PID 2>/dev/null || true
  fi
  exit
}

# Set up trap for script exit or interruption
trap cleanup EXIT INT TERM

# Start Fern docs server in the background
fern docs dev --port $PORT > /dev/null 2>&1 &
SERVER_PID=$!

# Wait for server to start
for i in $(seq 1 $MAX_RETRIES); do
  if curl -s --head --request GET $DOCS_URL | grep "200 OK" > /dev/null; then
    break
  fi
  
  if [ $i -eq $MAX_RETRIES ]; then
    echo "Timed out waiting for docs server to start"
    exit 1
  fi
  
  sleep $RETRY_INTERVAL
done

# Run the broken link checker and process output
echo "Checking for broken links..."

# Run BLC and pipe to awk for processing
# Build exclude parameters from the array
EXCLUDE_PARAMS=""
for domain in "${EXCLUDED_DOMAINS[@]}"; do
  EXCLUDE_PARAMS="$EXCLUDE_PARAMS --exclude $domain"
done

blc -r --filter-level 3 $DOCS_URL -f --requests 10 $EXCLUDE_PARAMS | \
awk '
BEGIN {
  current_page = ""
  has_relevant_broken_links = 0
  found_links = 0
  buffer = ""
  any_broken_links = 0
}

# Capture the current page being processed
/Getting links from:/ {
  # If we had a previous page with broken links, print its buffer
  if (has_relevant_broken_links) {
    print buffer
    print ""  # Extra newline for separation
    any_broken_links = 1
  }
  
  # Reset for new page
  current_page = $0
  buffer = current_page
  has_relevant_broken_links = 0
  found_links = 0
}

# Detect broken links but filter out HTTP_308 and HTTP_403
/BROKEN/ {
  found_links++
  if ($0 !~ /HTTP_308/ && $0 !~ /HTTP_403/) {
    has_relevant_broken_links++
    buffer = buffer "\n" $0
  }
}

# Process the summary line
/Finished!/ {
  if (has_relevant_broken_links) {
    # No summary line as requested
  } else {
    # If no non-308 broken links, reset the buffer for this page
    buffer = ""
  }
}

END {
  # Print the last buffer if it has broken links
  if (has_relevant_broken_links) {
    print buffer
    any_broken_links = 1
  }
  
  # Set exit code based on whether broken links were found
  exit any_broken_links
}
' > /tmp/blc_results

BLC_EXIT_CODE=$?

if [ $BLC_EXIT_CODE -eq 0 ]; then
  echo "No broken links found"
fi
