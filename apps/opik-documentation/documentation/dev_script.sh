#!/bin/bash

# Rebuild the reference docs
npm run docusaurus clean-api-docs all
npm run docusaurus gen-api-docs all

# Start the docs server and rebuild the cookbooks on change
concurrently \
  "docusaurus start" \
  "nodemon --watch docs/cookbook/ -e ipynb --exec 'jupyter nbconvert docs/cookbook/*.ipynb --to markdown'"
