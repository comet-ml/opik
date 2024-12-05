#!/bin/bash

# Rebuild the reference docs
npm run docusaurus clean-api-docs all
npm run docusaurus gen-api-docs all

# Rebuild the Cookbooks
jupyter nbconvert --clear-output --inplace docs/cookbook/*.ipynb
jupyter nbconvert docs/cookbook/*.ipynb --to markdown

# Build the docs
docusaurus build
