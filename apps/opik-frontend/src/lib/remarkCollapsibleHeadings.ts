/**
 * Utility functions for creating collapsible headings in Markdown
 *
 * This module provides functions to transform Markdown content to include
 * collapsible sections using HTML details/summary elements.
 */

export interface CollapsibleHeadingOptions {
  /**
   * Whether sections should be open by default
   */
  defaultOpen?: boolean;

  /**
   * Custom class name to add to collapsible sections
   */
  className?: string;

  /**
   * Custom class name for the summary element
   */
  summaryClassName?: string;

  /**
   * Custom class name for the content wrapper
   */
  contentClassName?: string;
}

/**
 * Transform Markdown content to make headings collapsible
 *
 * This function processes Markdown text and converts headings that start with ">"
 * into collapsible sections using HTML details/summary elements.
 *
 * @param markdown - The Markdown content to process
 * @param options - Configuration options for the collapsible sections
 * @returns The transformed Markdown with collapsible sections
 *
 * @example
 * ```typescript
 * const markdown = `
 * # Regular Heading
 * This is not collapsible
 *
 * ##> Collapsible Section
 * This content will be collapsible
 *
 * ### Subheading
 * More content here
 * `;
 *
 * const collapsibleMarkdown = makeHeadingsCollapsible(markdown, {
 *   defaultOpen: true,
 *   className: "my-collapsible"
 * });
 * ```
 */
export const makeHeadingsCollapsible = (
  markdown: string,
  options: CollapsibleHeadingOptions = {},
): string => {
  const {
    defaultOpen = false,
    className = "collapsible-heading",
    summaryClassName = "collapsible-heading-summary",
    contentClassName = "collapsible-heading-content",
  } = options;

  // Split the markdown into lines
  const lines = markdown.split("\n");
  const result: string[] = [];
  let inCollapsibleSection = false;
  let currentHeading = "";

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // Check if this line is a heading that should be collapsible
    const headingMatch = line.match(/^(#{1,6})\s*>\s*(.+)$/);

    if (headingMatch) {
      // If we were in a collapsible section, close it
      if (inCollapsibleSection) {
        result.push(`</div>`);
        result.push(`</details>`);
        result.push(""); // Empty line for spacing
      }

      // Start a new collapsible section
      const [, hashes, headingText] = headingMatch;
      currentHeading = headingText.trim();

      const openAttr = defaultOpen ? " open" : "";
      result.push(`<details${openAttr} class="${className}">`);
      result.push(`<summary class="${summaryClassName}">`);
      result.push(`${hashes} ${currentHeading}`);
      result.push(`</summary>`);
      result.push(`<div class="${contentClassName}">`);

      inCollapsibleSection = true;
    } else if (inCollapsibleSection) {
      // Check if we've reached another heading (non-collapsible)
      const regularHeadingMatch = line.match(/^(#{1,6})\s+(.+)$/);

      if (regularHeadingMatch) {
        // Close the current collapsible section
        result.push(`</div>`);
        result.push(`</details>`);
        result.push(""); // Empty line for spacing
        result.push(line);
        inCollapsibleSection = false;
      } else {
        // Add content to the collapsible section
        result.push(line);
      }
    } else {
      // Regular content
      result.push(line);
    }
  }

  // Close any remaining collapsible section
  if (inCollapsibleSection) {
    result.push(`</div>`);
    result.push(`</details>`);
  }

  return result.join("\n");
};

/**
 * Create a collapsible section wrapper for any content
 *
 * @param title - The title for the collapsible section
 * @param content - The content to wrap
 * @param options - Configuration options
 * @returns HTML string with details/summary structure
 */
export const createCollapsibleSection = (
  title: string,
  content: string,
  options: CollapsibleHeadingOptions = {},
): string => {
  const {
    defaultOpen = false,
    className = "collapsible-heading",
    summaryClassName = "collapsible-heading-summary",
    contentClassName = "collapsible-heading-content",
  } = options;

  const openAttr = defaultOpen ? " open" : "";

  return `<details${openAttr} class="${className}">
<summary class="${summaryClassName}">${title}</summary>
<div class="${contentClassName}">
${content}
</div>
</details>`;
};

/**
 * Check if a Markdown line is a collapsible heading
 *
 * @param line - The line to check
 * @returns True if the line is a collapsible heading
 */
export const isCollapsibleHeading = (line: string): boolean => {
  return /^(#{1,6})\s*>\s*(.+)$/.test(line);
};

/**
 * Extract the heading text from a collapsible heading line
 *
 * @param line - The collapsible heading line
 * @returns The heading text without the > marker
 */
export const extractCollapsibleHeadingText = (line: string): string => {
  const match = line.match(/^(#{1,6})\s*>\s*(.+)$/);
  return match ? match[2].trim() : "";
};
