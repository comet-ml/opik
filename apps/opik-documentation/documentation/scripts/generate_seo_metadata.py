#!/usr/bin/env python3
"""
Generate SEO metadata (headline, og:title, og:description) for Opik documentation pages.

This script:
1. Parses docs.yml to extract all page paths and titles
2. Reads each MDX file to check for existing SEO metadata
3. Generates missing metadata via OpenAI API
4. Updates MDX files with new frontmatter

Usage:
    # Dry run - show what would be updated
    python generate_seo_metadata.py --dry-run

    # Generate and apply metadata
    python generate_seo_metadata.py --apply

    # Generate for specific section only
    python generate_seo_metadata.py --section tracing --apply
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path
from typing import Optional

import yaml

try:
    import frontmatter
except ImportError:
    print("Error: python-frontmatter not installed. Run: pip install python-frontmatter")
    sys.exit(1)

try:
    from openai import OpenAI
except ImportError:
    print("Error: openai not installed. Run: pip install openai")
    sys.exit(1)


# Configuration
DOCS_YML_PATH = Path(__file__).parent.parent / "fern" / "docs.yml"
FERN_DOCS_DIR = Path(__file__).parent.parent / "fern"
OG_SITE_NAME = "Opik Documentation"

# SEO fields to check - if ANY of these exist, skip the page
SEO_FIELDS = ["headline", "og:title", "og:description"]


def load_docs_yml() -> dict:
    """Load and parse docs.yml configuration."""
    with open(DOCS_YML_PATH, "r") as f:
        return yaml.safe_load(f)


def extract_pages_from_navigation(navigation: list, section_filter: Optional[str] = None) -> list[dict]:
    """
    Recursively extract all pages from the navigation structure.
    
    Returns list of dicts with: path, title, section
    """
    pages = []
    
    def process_item(item: dict, current_section: str = ""):
        if isinstance(item, dict):
            # Check if it's a page
            if "page" in item and "path" in item:
                page_info = {
                    "title": item["page"],
                    "path": item["path"],
                    "section": current_section,
                    "slug": item.get("slug", ""),
                }
                
                # Apply section filter if specified
                if section_filter:
                    if section_filter.lower() in current_section.lower() or \
                       section_filter.lower() in item["path"].lower():
                        pages.append(page_info)
                else:
                    pages.append(page_info)
            
            # Check for section name
            section_name = item.get("section", current_section)
            
            # Process nested contents
            if "contents" in item:
                for content in item["contents"]:
                    process_item(content, section_name)
            
            # Process layout
            if "layout" in item:
                for layout_item in item["layout"]:
                    process_item(layout_item, section_name)
    
    for nav_item in navigation:
        # Handle tabs
        if "tab" in nav_item:
            tab_name = nav_item["tab"]
            if "layout" in nav_item:
                for layout_item in nav_item["layout"]:
                    process_item(layout_item, tab_name)
        else:
            process_item(nav_item, "")
    
    return pages


def has_existing_seo_metadata(mdx_path: Path) -> bool:
    """Check if MDX file already has SEO metadata in frontmatter."""
    try:
        post = frontmatter.load(mdx_path)
        # Check if ANY of the SEO fields exist
        for field in SEO_FIELDS:
            if field in post.metadata:
                return True
        return False
    except Exception:
        return False


def get_existing_frontmatter(mdx_path: Path) -> dict:
    """Get existing frontmatter from MDX file."""
    try:
        post = frontmatter.load(mdx_path)
        return dict(post.metadata)
    except Exception:
        return {}


def get_page_content_preview(mdx_path: Path, max_chars: int = 500) -> str:
    """Get a preview of the page content for context."""
    try:
        post = frontmatter.load(mdx_path)
        content = post.content[:max_chars]
        # Clean up MDX-specific syntax for better LLM understanding
        content = re.sub(r'<[^>]+>', '', content)  # Remove HTML/JSX tags
        content = re.sub(r'\{[^}]+\}', '', content)  # Remove JSX expressions
        return content.strip()
    except Exception:
        return ""


def generate_seo_metadata(
    title: str,
    section: str,
    content_preview: str,
    client: OpenAI
) -> dict:
    """Generate SEO metadata using OpenAI API."""
    
    prompt = f"""Generate SEO metadata for this documentation page.

Page Title: {title}
Section: {section}
Content Preview: {content_preview}

Return a JSON object with these fields:
{{
  "og_title": "Compelling title under 60 chars, should include 'Opik' naturally",
  "og_description": "Action-oriented description, 150-160 chars max, explain the value/benefit"
}}

Guidelines:
- og_title: Include the main topic and reference Opik naturally (e.g., "with Opik", "in Opik", "- Opik")
- og_description: Start with an action verb (Learn, Capture, Evaluate, Monitor, Build, Configure, etc.)
- Be specific about what the page teaches or enables
- Avoid generic marketing language, be technical and precise
- Do NOT use quotes around the values in your response

Return ONLY the JSON object, no explanation."""

    try:
        response = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[
                {"role": "system", "content": "You are a technical documentation SEO specialist. Return only valid JSON."},
                {"role": "user", "content": prompt}
            ],
            temperature=0.7,
            max_tokens=200
        )
        
        result_text = response.choices[0].message.content.strip()
        
        # Clean up response - remove markdown code blocks if present
        if result_text.startswith("```"):
            result_text = re.sub(r'^```\w*\n?', '', result_text)
            result_text = re.sub(r'\n?```$', '', result_text)
        
        result = json.loads(result_text)
        return result
    except json.JSONDecodeError as e:
        print(f"  Warning: Failed to parse LLM response as JSON: {e}")
        print(f"  Response was: {result_text}")
        return None
    except Exception as e:
        print(f"  Warning: Failed to generate metadata: {e}")
        return None


def update_mdx_frontmatter(mdx_path: Path, new_metadata: dict, dry_run: bool = True) -> bool:
    """Update MDX file with new frontmatter metadata."""
    try:
        post = frontmatter.load(mdx_path)
        
        # Merge new metadata with existing (new values don't overwrite existing)
        for key, value in new_metadata.items():
            if key not in post.metadata:
                post.metadata[key] = value
        
        if dry_run:
            print(f"  Would add: {new_metadata}")
            return True
        
        # Write back to file
        with open(mdx_path, "w") as f:
            f.write(frontmatter.dumps(post))
        
        return True
    except Exception as e:
        print(f"  Error updating {mdx_path}: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description="Generate SEO metadata for Opik documentation")
    parser.add_argument("--dry-run", action="store_true", help="Show what would be updated without making changes")
    parser.add_argument("--apply", action="store_true", help="Apply changes to MDX files")
    parser.add_argument("--section", type=str, help="Only process pages in this section")
    parser.add_argument("--limit", type=int, help="Limit number of pages to process (for testing)")
    args = parser.parse_args()
    
    if not args.dry_run and not args.apply:
        print("Error: Must specify either --dry-run or --apply")
        parser.print_help()
        sys.exit(1)
    
    # Check for API key
    api_key = os.environ.get("OPENAI_API_KEY")
    if not api_key:
        print("Error: OPENAI_API_KEY environment variable not set")
        sys.exit(1)
    
    client = OpenAI(api_key=api_key)
    
    # Load docs.yml
    print(f"Loading {DOCS_YML_PATH}...")
    docs_config = load_docs_yml()
    
    # Extract pages
    print("Extracting pages from navigation...")
    pages = extract_pages_from_navigation(docs_config.get("navigation", []), args.section)
    print(f"Found {len(pages)} pages")
    
    if args.limit:
        pages = pages[:args.limit]
        print(f"Limited to {len(pages)} pages")
    
    # Process each page
    updated = 0
    skipped = 0
    errors = 0
    
    for i, page in enumerate(pages, 1):
        mdx_path = FERN_DOCS_DIR / page["path"]
        
        print(f"\n[{i}/{len(pages)}] {page['title']}")
        print(f"  Path: {page['path']}")
        
        # Check if file exists
        if not mdx_path.exists():
            print(f"  Skipping: File not found")
            errors += 1
            continue
        
        # Check for existing SEO metadata
        if has_existing_seo_metadata(mdx_path):
            print(f"  Skipping: Already has SEO metadata")
            skipped += 1
            continue
        
        # Get content preview for context
        content_preview = get_page_content_preview(mdx_path)
        
        # Generate metadata
        print(f"  Generating metadata...")
        metadata = generate_seo_metadata(
            title=page["title"],
            section=page["section"],
            content_preview=content_preview,
            client=client
        )
        
        if not metadata:
            print(f"  Skipping: Failed to generate metadata")
            errors += 1
            continue
        
        # Prepare frontmatter
        new_frontmatter = {
            "title": page["title"],
            "headline": f"{page['title']} | Opik Documentation",
            "og:site_name": OG_SITE_NAME,
            "og:title": metadata["og_title"],
            "og:description": metadata["og_description"],
        }
        
        # Update file
        if update_mdx_frontmatter(mdx_path, new_frontmatter, dry_run=args.dry_run):
            updated += 1
            print(f"  og:title: {metadata['og_title']}")
            print(f"  og:description: {metadata['og_description'][:80]}...")
        else:
            errors += 1
    
    # Summary
    print(f"\n{'='*60}")
    print(f"Summary:")
    print(f"  Total pages: {len(pages)}")
    print(f"  {'Would update' if args.dry_run else 'Updated'}: {updated}")
    print(f"  Skipped (existing metadata): {skipped}")
    print(f"  Errors: {errors}")
    
    if args.dry_run:
        print(f"\nTo apply changes, run with --apply instead of --dry-run")


if __name__ == "__main__":
    main()

