#!/bin/bash

# Convert the files to markdown and move to the correct directory
jupyter nbconvert -ClearOutputPreprocessor.enabled=True --output-dir=fern/docs/cookbook docs/cookbook/*.ipynb --to markdown
for file in fern/docs/cookbook/*.md; do mv "$file" "${file%.md}.mdx"; done
