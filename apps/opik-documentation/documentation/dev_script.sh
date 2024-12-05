#!/bin/bash

# Rebuild the reference docs
npm run docusaurus clean-api-docs all
npm run docusaurus gen-api-docs all

# Convert the files to markdown if that has not already been done
jupyter nbconvert docs/cookbook/*.ipynb --to markdown

# Start the docs server and rebuild the cookbooks on change
npx concurrently \
  "docusaurus start" \
  "nodemon --watch docs/cookbook/ -e ipynb --exec 'jupyter nbconvert docs/cookbook/*.ipynb --to markdown'"
