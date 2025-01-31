#!/bin/bash

# Convert the files to markdown if that has not already been done
jupyter nbconvert -ClearOutputPreprocessor.enabled=True cookbook/*.ipynb --to markdown && for file in cookbook/*.md; do mv "$file" "${file%.md}.mdx"; done

# Start the docs server and rebuild the cookbooks on change
npx concurrently \
  "mintlify dev" \
  "nodemon --watch docs/cookbook/ -e ipynb --exec 'jupyter nbconvert --ClearOutputPreprocessor.enabled=True docs/cookbook/*.ipynb --to markdown && for file in docs/cookbook/*.md; do mv "$file" "${file%.md}.mdx"; done'"
