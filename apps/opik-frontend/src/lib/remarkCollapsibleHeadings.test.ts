import { describe, it, expect } from "vitest";
import {
  makeHeadingsCollapsible,
  createCollapsibleSection,
  isCollapsibleHeading,
  extractCollapsibleHeadingText,
} from "./remarkCollapsibleHeadings";

describe("remarkCollapsibleHeadings", () => {
  describe("makeHeadingsCollapsible", () => {
    it("should transform collapsible headings correctly", () => {
      const markdown = `# Regular Heading
This is not collapsible

##> Collapsible Section
This content will be collapsible

### Subheading
More content here

## Regular Heading 2
This is also not collapsible`;

      const result = makeHeadingsCollapsible(markdown);

      expect(result).toContain('<details class="collapsible-heading">');
      expect(result).toContain('<summary class="collapsible-heading-summary">');
      expect(result).toContain("## Collapsible Section");
      expect(result).toContain('<div class="collapsible-heading-content">');
      expect(result).toContain("This content will be collapsible");
      expect(result).toContain("### Subheading");
      expect(result).toContain("More content here");
      expect(result).toContain("</div>");
      expect(result).toContain("</details>");
    });

    it("should handle multiple collapsible sections", () => {
      const markdown = `##> First Section
Content 1

##> Second Section
Content 2`;

      const result = makeHeadingsCollapsible(markdown);

      const detailsCount = (result.match(/<details/g) || []).length;
      expect(detailsCount).toBe(2);
    });

    it("should handle defaultOpen option", () => {
      const markdown = `##> Collapsible Section
Content here`;

      const result = makeHeadingsCollapsible(markdown, { defaultOpen: true });

      expect(result).toContain('<details open class="collapsible-heading">');
    });

    it("should handle custom class names", () => {
      const markdown = `##> Collapsible Section
Content here`;

      const result = makeHeadingsCollapsible(markdown, {
        className: "my-collapsible",
        summaryClassName: "my-summary",
        contentClassName: "my-content",
      });

      expect(result).toContain('<details class="my-collapsible">');
      expect(result).toContain('<summary class="my-summary">');
      expect(result).toContain('<div class="my-content">');
    });
  });

  describe("createCollapsibleSection", () => {
    it("should create a collapsible section wrapper", () => {
      const result = createCollapsibleSection("Test Title", "Test content");

      expect(result).toContain('<details class="collapsible-heading">');
      expect(result).toContain(
        '<summary class="collapsible-heading-summary">Test Title</summary>',
      );
      expect(result).toContain('<div class="collapsible-heading-content">');
      expect(result).toContain("Test content");
      expect(result).toContain("</div>");
      expect(result).toContain("</details>");
    });

    it("should handle defaultOpen option", () => {
      const result = createCollapsibleSection("Test Title", "Test content", {
        defaultOpen: true,
      });

      expect(result).toContain('<details open class="collapsible-heading">');
    });
  });

  describe("isCollapsibleHeading", () => {
    it("should identify collapsible headings", () => {
      expect(isCollapsibleHeading("##> Collapsible Section")).toBe(true);
      expect(isCollapsibleHeading("###> Another Section")).toBe(true);
      expect(isCollapsibleHeading("## Regular Section")).toBe(false);
      expect(isCollapsibleHeading("Not a heading")).toBe(false);
    });
  });

  describe("extractCollapsibleHeadingText", () => {
    it("should extract heading text correctly", () => {
      expect(extractCollapsibleHeadingText("##> Collapsible Section")).toBe(
        "Collapsible Section",
      );
      expect(extractCollapsibleHeadingText("###> Another Section")).toBe(
        "Another Section",
      );
      expect(extractCollapsibleHeadingText("## Regular Section")).toBe("");
      expect(extractCollapsibleHeadingText("Not a heading")).toBe("");
    });
  });
});
