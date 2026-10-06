import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import ReactMarkdown from "react-markdown";
import { MARKDOWN_REHYPE_PLUGINS } from "./markdown";
import { createRemarkSearchPlugin } from "@/shared/SyntaxHighlighter/hooks/useMarkdownSearch/plugin";
import { VisitorNode } from "@/shared/SyntaxHighlighter/types";

const renderWithSearch = (
  markdown: string,
  searchTerm: string,
  currentMatchIndex = 0,
) => {
  const searchPlugin = () => (tree: VisitorNode) =>
    createRemarkSearchPlugin(searchTerm, { value: 0 }, currentMatchIndex)(tree);

  return render(
    <ReactMarkdown
      remarkPlugins={[searchPlugin]}
      rehypePlugins={MARKDOWN_REHYPE_PLUGINS}
    >
      {markdown}
    </ReactMarkdown>,
  );
};

describe("MARKDOWN_REHYPE_PLUGINS", () => {
  it("keeps search highlight attributes on marks", () => {
    const { container } = renderWithSearch("foo bar foo", "foo", 1);

    const marks = Array.from(container.querySelectorAll("mark"));
    expect(marks).toHaveLength(2);
    expect(marks.map((mark) => mark.dataset.matchIndex)).toEqual(["0", "1"]);
    marks.forEach((mark) => {
      expect(mark.dataset.currentMatchIndex).toBe("1");
      expect(mark.className).not.toBe("");
    });
    expect(
      container.querySelector(
        '[data-match-index="1"][data-current-match-index="1"]',
      ),
    ).not.toBeNull();
  });

  it("keeps search highlights inside inline code and code blocks", () => {
    const { container } = renderWithSearch(
      "`foo` text\n\n```\nfoo\n```",
      "foo",
    );

    expect(container.querySelectorAll("code mark")).toHaveLength(2);
  });

  it("renders standard markdown elements", () => {
    const { container } = renderWithSearch(
      "# Title\n\n**bold** [link](https://example.com)",
      "missing",
    );

    expect(container.querySelector("h1")?.textContent).toBe("Title");
    expect(container.querySelector("strong")?.textContent).toBe("bold");
    expect(container.querySelector("a")?.getAttribute("href")).toBe(
      "https://example.com",
    );
  });
});
