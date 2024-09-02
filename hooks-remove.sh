#!/bin/sh

# Remove the pre-commit hook from the Git hooks directory
if [ -f .git/hooks/pre-commit ]; then
  rm .git/hooks/pre-commit
  echo "Pre-commit hook removed."
else
  echo "No pre-commit hook found."
fi
