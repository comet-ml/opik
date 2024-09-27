#!/bin/bash
# Rebuild the Cookbooks
jupyter nbconvert docs/cookbook/*.ipynb --clear-output --to markdown 

# Build the docs
docusaurus build
