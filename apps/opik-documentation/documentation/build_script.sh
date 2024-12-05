#!/bin/bash

# Rebuild the reference docs
npm run docusaurus clean-api-docs all
npm run docusaurus gen-api-docs all

# Rebuild the Cookbooks
jupyter nbconvert docs/cookbook/*.ipynb --clear-output --to markdown 

# Build the docs
docusaurus build
