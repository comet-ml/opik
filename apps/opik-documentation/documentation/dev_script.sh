#!/bin/bash

# Convert the files to markdown if that has not already been done
jupyter nbconvert cookbook/*.ipynb --to markdown

# Start the docs server and rebuild the cookbooks on change
npx concurrently \
  "mintlify dev" \
  "nodemon --watch docs/cookbook/ -e ipynb --exec 'jupyter nbconvert docs/cookbook/*.ipynb --to markdown'"
