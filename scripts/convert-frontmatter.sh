#!/usr/bin/env bash
# Convert frontmatter between agent tool formats
# Usage: convert-frontmatter.sh <direction> <input-file> <output-file>
# direction: cursor-to-claude | claude-to-cursor | cursor-to-codex

set -euo pipefail

direction="$1"
input="$2"
output="$3"

if [[ "$direction" == "cursor-to-claude" ]]; then
    # Convert Cursor format (globs/alwaysApply) to Claude format (paths)
    awk '
    BEGIN { in_frontmatter=0; frontmatter_done=0; globs=""; always_apply=0 }
    /^---$/ && !frontmatter_done {
        if (in_frontmatter) {
            # End of frontmatter - output Claude format
            print "---"
            if (!always_apply && globs != "") {
                print "paths:"
                print "  - \"" globs "\""
            }
            print "---"
            frontmatter_done=1
        } else {
            in_frontmatter=1
        }
        next
    }
    in_frontmatter && !frontmatter_done {
        if (/^globs:/) {
            gsub(/^globs:[[:space:]]*/, "")
            globs=$0
        } else if (/^alwaysApply:[[:space:]]*true/) {
            always_apply=1
        }
        next
    }
    { print }
    ' "$input" > "$output"

elif [[ "$direction" == "claude-to-cursor" ]]; then
    # Convert Claude format (paths) to Cursor format (globs/alwaysApply)
    awk '
    BEGIN { in_frontmatter=0; frontmatter_done=0; paths="" }
    /^---$/ && !frontmatter_done {
        if (in_frontmatter) {
            # End of frontmatter - output Cursor format
            print "---"
            print "description: "
            if (paths != "") {
                print "globs: " paths
                print "alwaysApply: false"
            } else {
                print "globs:"
                print "alwaysApply: true"
            }
            print "---"
            frontmatter_done=1
        } else {
            in_frontmatter=1
        }
        next
    }
    in_frontmatter && !frontmatter_done {
        if (/^[[:space:]]*-[[:space:]]*".*"$/) {
            gsub(/^[[:space:]]*-[[:space:]]*"/, "")
            gsub(/"$/, "")
            paths=$0
        }
        next
    }
    { print }
    ' "$input" > "$output"
elif [[ "$direction" == "cursor-to-codex" ]]; then
    # Strip frontmatter for Codex-friendly markdown documents.
    awk '
    BEGIN { in_frontmatter=0; frontmatter_done=0 }
    /^---$/ && !frontmatter_done {
        if (in_frontmatter) {
            in_frontmatter=0
            frontmatter_done=1
        } else {
            in_frontmatter=1
        }
        next
    }
    in_frontmatter && !frontmatter_done { next }
    { print }
    ' "$input" > "$output"
else
    echo "Unknown direction: $direction" >&2
    exit 1
fi
