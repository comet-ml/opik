#!/bin/bash

# Start the docs server and rebuild the cookbooks on change
npx concurrently \
  "fern docs dev" \
  "nodemon --watch docs/cookbook/ -e ipynb --exec 'sh ./update_cookbooks.sh'"
