#!/bin/bash
# Setup script for environment variables
# Run this script to set up your local environment

echo "Setting up environment variables for MCP..."

# Load environment variables from .env.local if it exists
if [ -f .env.local ]; then
    echo "Loading environment variables from .env.local..."
    export $(cat .env.local | grep -v '^#' | xargs)
    echo "✅ Environment variables loaded"
else
    echo "⚠️  .env.local not found. Please create it with your tokens."
    echo "Example:"
    echo "GITHUB_PERSONAL_ACCESS_TOKEN=your_token_here"
fi

# Verify environment variables are set
if [ -n "$GITHUB_PERSONAL_ACCESS_TOKEN" ]; then
    echo "✅ GITHUB_PERSONAL_ACCESS_TOKEN is set"
else
    echo "❌ GITHUB_PERSONAL_ACCESS_TOKEN is not set"
fi
