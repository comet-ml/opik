#!/bin/sh

set -e

echo "Starting the Demo Data creation task"

if [ "$CREATE_DEMO_DATA" = "true" ]; then
    if ! output=$(wget --quiet --post-data '{"workspace":"default","apiKey":"1234"}' \
                    --header "Content-Type: application/json" \
                    -O - http://python-backend:8000/v1/private/post_user_signup); then
        echo "❌ Failed to create demo data:"
        echo "$output"
    else
        echo "✅ Demo data created"
    fi
else
  echo "CREATE_DEMO_DATA is set to false, skipping the creation of demo data"
fi
