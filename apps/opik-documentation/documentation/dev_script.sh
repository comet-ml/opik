#!/bin/bash

# Convert the files to markdown if that has not already been done
jupyter nbconvert -ClearOutputPreprocessor.enabled=True --output-dir=fern/docs/cookbook docs/cookbook/*.ipynb --to markdown

for file in fern/docs/cookbook/*.md; do mv "$file" "${file%.md}.mdx"; done

# Start the docs server and rebuild the cookbooks on change
npx concurrently \
  "fern docs dev" \
  "nodemon --watch docs/cookbook/ -e ipynb --exec 'jupyter nbconvert --ClearOutputPreprocessor.enabled=True --output-dir=fern/docs/cookbook docs/cookbook/*.ipynb --to markdown && for file in fern/docs/cookbook/*.md; do mv "$file" "${file%.md}.mdx"; done'"
