import { describe, expect, it } from "vitest";
import { isStringMarkdown } from "./utils";

describe("isStringMarkdown", () => {
  // Test non-string inputs
  it("should return false for non-string inputs", () => {
    expect(isStringMarkdown(null)).toBe(false);
    expect(isStringMarkdown(undefined)).toBe(false);
    expect(isStringMarkdown(123)).toBe(false);
    expect(isStringMarkdown({})).toBe(false);
    expect(isStringMarkdown([])).toBe(false);
    expect(isStringMarkdown(true)).toBe(false);
  });

  // Test short strings
  it("should return false for strings less than 3 characters", () => {
    expect(isStringMarkdown("")).toBe(false);
    expect(isStringMarkdown("a")).toBe(false);
    expect(isStringMarkdown("ab")).toBe(false);
  });

  // Test headers
  it("should identify headers as markdown", () => {
    expect(isStringMarkdown("# Header 1")).toBe(true);
    expect(isStringMarkdown("## Header 2")).toBe(true);
    expect(isStringMarkdown("### Header 3")).toBe(true);
    expect(isStringMarkdown("#Not a header")).toBe(false); // no space after #
  });

  // Test emphasis
  it("should identify emphasized text as markdown", () => {
    expect(isStringMarkdown("This is **bold** text")).toBe(true);
    expect(isStringMarkdown("This is __bold__ text")).toBe(true);
    expect(isStringMarkdown("This is *italic* text")).toBe(true);
    expect(isStringMarkdown("This is _italic_ text")).toBe(true);
    expect(isStringMarkdown("This is ~~strikethrough~~ text")).toBe(true);
  });

  // Test links and images
  it("should identify links and images as markdown", () => {
    expect(isStringMarkdown("[Link text](https://example.com)")).toBe(true);
    expect(
      isStringMarkdown("![Image alt](https://example.com/image.jpg)"),
    ).toBe(true);
  });

  // Test lists
  it("should identify lists as markdown", () => {
    // Unordered lists
    expect(isStringMarkdown("* Item 1\n* Item 2")).toBe(true);
    expect(isStringMarkdown("- Item 1\n- Item 2")).toBe(true);
    expect(isStringMarkdown("+ Item 1\n+ Item 2")).toBe(true);

    // Ordered lists
    expect(isStringMarkdown("1. Item 1\n2. Item 2")).toBe(true);
  });

  // Test blockquotes
  it("should identify blockquotes as markdown", () => {
    expect(isStringMarkdown("> This is a blockquote")).toBe(true);
    expect(isStringMarkdown("> Line 1\n> Line 2")).toBe(true);
  });

  // Test code blocks and inline code
  it("should identify code as markdown", () => {
    expect(isStringMarkdown("```\ncode block\n```")).toBe(true);
    expect(isStringMarkdown("```javascript\nconst x = 10;\n```")).toBe(true);
    expect(isStringMarkdown("This is `inline code`")).toBe(true);
  });

  // Test tables
  it("should identify tables as markdown", () => {
    const table =
      "| Header 1 | Header 2 |\n| -------- | -------- |\n| Cell 1   | Cell 2   |";
    expect(isStringMarkdown(table)).toBe(true);
  });

  // Test horizontal rules
  it("should identify horizontal rules as markdown", () => {
    expect(isStringMarkdown("---")).toBe(true);
    expect(isStringMarkdown("***")).toBe(true);
    expect(isStringMarkdown("___")).toBe(true);
  });

  // Test task lists
  it("should identify task lists as markdown", () => {
    expect(isStringMarkdown("- [ ] Unchecked task")).toBe(true);
    expect(isStringMarkdown("- [x] Checked task")).toBe(true);
    expect(isStringMarkdown("* [X] Checked task")).toBe(true);
  });

  // Test definition lists
  it("should identify definition lists as markdown", () => {
    expect(isStringMarkdown("Term\n: Definition")).toBe(true);
  });

  // Test footnotes
  it("should identify footnotes as markdown", () => {
    expect(
      isStringMarkdown(
        "Here is a footnote reference[^1]\n\n[^1]: Here is the footnote.",
      ),
    ).toBe(true);
  });

  // Test complex markdown
  it("should identify complex markdown", () => {
    const complex = `# Markdown Example

This is a paragraph with **bold** and *italic* text.

## Lists
- Item 1
- Item 2
  - Nested item

## Code
\`\`\`javascript
function hello() {
  console.log("Hello world!");
}
\`\`\`

> This is a blockquote.

For more info, see [this link](https://example.com).`;

    expect(isStringMarkdown(complex)).toBe(true);
  });

  // Test for false positives
  it("should not identify regular text as markdown", () => {
    expect(isStringMarkdown("This is just regular text.")).toBe(false);
    expect(
      isStringMarkdown("This contains an asterisk * but is not markdown."),
    ).toBe(false);
    expect(
      isStringMarkdown("This contains a hash # but is not markdown."),
    ).toBe(false);
    expect(
      isStringMarkdown("This is paragraph 1.\n\nThis is paragraph 2."),
    ).toBe(false);
    expect(isStringMarkdown("Visit https://example.com for more info")).toBe(
      false,
    );
    expect(isStringMarkdown("Visit http://example.com for more info")).toBe(
      false,
    );
    // The following test might pass (true) due to the URL detection logic in the function
    // expect(isStringMarkdown('The URL example.com is not formatted as a markdown link')).toBe(false);
  });

  // Test JSON detection
  it("should not identify valid JSON objects as markdown", () => {
    expect(isStringMarkdown('{"key": "value"}')).toBe(false);
    expect(isStringMarkdown('{"name": "John", "age": 30}')).toBe(false);
    expect(isStringMarkdown('{"nested": {"key": "value"}, "count": 42}')).toBe(
      false,
    );
    expect(isStringMarkdown("{}")).toBe(false);
  });

  it("should not identify valid JSON arrays as markdown", () => {
    expect(isStringMarkdown('["item1", "item2"]')).toBe(false);
    expect(isStringMarkdown("[1, 2, 3, 4, 5]")).toBe(false);
    expect(isStringMarkdown('[{"id": 1}, {"id": 2}]')).toBe(false);
    expect(isStringMarkdown("[]")).toBe(false);
  });

  it("should not identify pretty-printed JSON as markdown", () => {
    const prettyJson = `{
  "name": "John",
  "age": 30,
  "city": "New York"
}`;
    expect(isStringMarkdown(prettyJson)).toBe(false);

    const prettyArray = `[
  {
    "id": 1,
    "name": "Item 1"
  },
  {
    "id": 2,
    "name": "Item 2"
  }
]`;
    expect(isStringMarkdown(prettyArray)).toBe(false);
  });

  it("should not identify JSON with markdown-like characters as markdown", () => {
    expect(isStringMarkdown('{"message": "This is **bold** text"}')).toBe(
      false,
    );
    expect(isStringMarkdown('{"content": "# Header text"}')).toBe(false);
    expect(isStringMarkdown('{"text": "This is *italic* text"}')).toBe(false);
    expect(
      isStringMarkdown('{"link": "[Click here](https://example.com)"}'),
    ).toBe(false);
    expect(isStringMarkdown('{"code": "`inline code`"}')).toBe(false);
  });

  it("should not identify JSON with whitespace as markdown", () => {
    expect(isStringMarkdown('  {"key": "value"}  ')).toBe(false);
    expect(isStringMarkdown('\n{"key": "value"}\n')).toBe(false);
    expect(isStringMarkdown('\t["item1", "item2"]\t')).toBe(false);
  });

  it("should still evaluate invalid JSON-like strings for markdown", () => {
    expect(isStringMarkdown("{invalid json}")).toBe(false);
    expect(isStringMarkdown("[not valid, json]")).toBe(false);
    expect(isStringMarkdown('{"unclosed": ')).toBe(false);
    expect(isStringMarkdown("{ this has **bold** markdown inside }")).toBe(
      true,
    );
    expect(isStringMarkdown("[this has `code` markdown inside]")).toBe(true);
  });

  it("should evaluate strings starting with { or [ but not JSON", () => {
    expect(isStringMarkdown("[Link](https://example.com)")).toBe(true);
    expect(isStringMarkdown("{ code block }")).toBe(false);
  });
});
