import { describe, it, expect, vi } from "vitest";
import { render, fireEvent } from "@testing-library/react";
import LinkifyText from "./LinkifyText";

const getLinks = (container: HTMLElement) =>
  Array.from(container.querySelectorAll("a"));

describe("LinkifyText", () => {
  describe("basic URL detection", () => {
    it("should linkify an https URL", () => {
      const { container } = render(
        <LinkifyText>{"Check https://example.com for details"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("https://example.com/");
      expect(links[0].textContent).toBe("https://example.com");
    });

    it("should linkify an http URL", () => {
      const { container } = render(
        <LinkifyText>{"Visit http://example.com page"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("http://example.com/");
    });

    it("should linkify a URL-only string", () => {
      const { container } = render(
        <LinkifyText>{"https://example.com"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].textContent).toBe("https://example.com");
    });
  });

  describe("multiple URLs", () => {
    it("should linkify multiple URLs in one string", () => {
      const { container } = render(
        <LinkifyText>
          {"First: https://one.com then https://two.com and https://three.com"}
        </LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(3);
      expect(links[0].href).toBe("https://one.com/");
      expect(links[1].href).toBe("https://two.com/");
      expect(links[2].href).toBe("https://three.com/");
    });

    it("should linkify URLs separated by newlines", () => {
      const { container } = render(
        <LinkifyText>
          {"https://a.com\nhttps://b.com\nhttps://c.com"}
        </LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(3);
    });
  });

  describe("URLs with special characters", () => {
    it("should linkify URL with query params and fragment", () => {
      const { container } = render(
        <LinkifyText>
          {"https://example.com/path?q=hello&lang=en#section-2"}
        </LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toContain("q=hello");
      expect(links[0].href).toContain("#section-2");
    });

    it("should linkify URL with percent-encoded characters", () => {
      const { container } = render(
        <LinkifyText>
          {"https://example.com/path/to/resource%20with%20spaces"}
        </LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
    });

    it("should linkify URL with port number", () => {
      const { container } = render(
        <LinkifyText>{"https://example.com:8080/path"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toContain(":8080");
    });
  });

  describe("non-URL strings (should pass through unchanged)", () => {
    it("should render plain text without any links", () => {
      const { container } = render(
        <LinkifyText>{"Just plain text with no links"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(0);
      expect(container.textContent).toBe("Just plain text with no links");
    });

    it("should render numeric string without links", () => {
      const { container } = render(<LinkifyText>{"12345.678"}</LinkifyText>);
      expect(getLinks(container)).toHaveLength(0);
    });

    it("should render empty string", () => {
      const { container } = render(<LinkifyText>{""}</LinkifyText>);
      expect(getLinks(container)).toHaveLength(0);
      expect(container.textContent).toBe("");
    });

    it("should render whitespace-only string", () => {
      const { container } = render(<LinkifyText>{"   "}</LinkifyText>);
      expect(getLinks(container)).toHaveLength(0);
    });
  });

  describe("invalid protocols (should NOT linkify)", () => {
    it("should not linkify ftp:// URLs", () => {
      const { container } = render(
        <LinkifyText>{"ftp://files.example.com/data.csv"}</LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(0);
    });

    it("should not linkify mailto: links", () => {
      const { container } = render(
        <LinkifyText>{"mailto:user@example.com"}</LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(0);
    });

    it("should not linkify file:// URLs", () => {
      const { container } = render(
        <LinkifyText>{"file:///Users/local/file.txt"}</LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(0);
    });

    it("should not linkify URLs without protocol", () => {
      const { container } = render(
        <LinkifyText>{"example.com/path"}</LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(0);
    });

    it("should not linkify misspelled protocol", () => {
      const { container } = render(
        <LinkifyText>{"htp://typo.com"}</LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(0);
    });
  });

  describe("security edge cases", () => {
    it("should not linkify javascript: protocol", () => {
      const { container } = render(
        <LinkifyText>{"javascript:alert('xss')"}</LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(0);
    });

    it("should not execute script tags in content", () => {
      const { container } = render(
        <LinkifyText>
          {'<script>alert("xss")</script> https://example.com'}
        </LinkifyText>,
      );
      expect(container.querySelectorAll("script")).toHaveLength(0);
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("https://example.com/");
    });

    it("should not linkify data: URIs", () => {
      const { container } = render(
        <LinkifyText>
          {"data:text/html,<script>alert('xss')</script>"}
        </LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(0);
    });

    it("should safely handle XSS in URL path", () => {
      const { container } = render(
        <LinkifyText>
          {'https://example.com/"><script>alert(1)</script>'}
        </LinkifyText>,
      );
      expect(container.querySelectorAll("script")).toHaveLength(0);
    });
  });

  describe("link attributes", () => {
    it("should set target=_blank on links", () => {
      const { container } = render(
        <LinkifyText>{"https://example.com"}</LinkifyText>,
      );
      const link = getLinks(container)[0];
      expect(link.target).toBe("_blank");
    });

    it("should set rel=noopener noreferrer on links", () => {
      const { container } = render(
        <LinkifyText>{"https://example.com"}</LinkifyText>,
      );
      const link = getLinks(container)[0];
      expect(link.rel).toBe("noopener noreferrer");
    });

    it("should apply link styling classes", () => {
      const { container } = render(
        <LinkifyText>{"https://example.com"}</LinkifyText>,
      );
      const link = getLinks(container)[0];
      expect(link.className).toContain("text-blue-600");
      expect(link.className).toContain("underline");
      expect(link.className).toContain("break-all");
    });
  });

  describe("click behavior", () => {
    it("should stop propagation on link click", () => {
      const parentHandler = vi.fn();
      const { container } = render(
        <div onClick={parentHandler}>
          <LinkifyText>{"https://example.com"}</LinkifyText>
        </div>,
      );
      const link = getLinks(container)[0];
      fireEvent.click(link);
      expect(parentHandler).not.toHaveBeenCalled();
    });
  });

  describe("URL positions in text", () => {
    it("should linkify URL at the start of string", () => {
      const { container } = render(
        <LinkifyText>{"https://start.com is at the start"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("https://start.com/");
    });

    it("should linkify URL at the end of string", () => {
      const { container } = render(
        <LinkifyText>{"URL at the end: https://end.com"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("https://end.com/");
    });

    it("should linkify URL wrapped in parentheses", () => {
      const { container } = render(
        <LinkifyText>{"(https://example.com)"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("https://example.com/");
    });

    it("should linkify URL wrapped in brackets", () => {
      const { container } = render(
        <LinkifyText>{"[https://example.com]"}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
    });

    it("should linkify URL followed by comma", () => {
      const { container } = render(
        <LinkifyText>
          {"Visit https://example.com, then continue."}
        </LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("https://example.com/");
    });

    it("should linkify URL followed by period", () => {
      const { container } = render(
        <LinkifyText>{"See https://example.com."}</LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toBe("https://example.com/");
    });
  });

  describe("non-string children", () => {
    it("should pass through non-string children to Linkify", () => {
      const { container } = render(
        <LinkifyText>
          <span>{"Text with https://example.com inside"}</span>
        </LinkifyText>,
      );
      const links = getLinks(container);
      expect(links).toHaveLength(1);
    });

    it("should handle null children", () => {
      const { container } = render(<LinkifyText>{null}</LinkifyText>);
      expect(container.textContent).toBe("");
    });

    it("should handle undefined children", () => {
      const { container } = render(<LinkifyText>{undefined}</LinkifyText>);
      expect(container.textContent).toBe("");
    });

    it("should handle numeric children", () => {
      const { container } = render(<LinkifyText>{42}</LinkifyText>);
      expect(getLinks(container)).toHaveLength(0);
      expect(container.textContent).toBe("42");
    });
  });

  describe("mixed content", () => {
    it("should linkify URLs in error messages", () => {
      const errorText =
        "Error: Connection refused at https://api.internal.com:8443/v2/health\n" +
        "Traceback (most recent call last):\n" +
        '  File "/app/main.py", line 42';
      const { container } = render(<LinkifyText>{errorText}</LinkifyText>);
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toContain("api.internal.com");
    });

    it("should linkify URLs in JSON-like strings", () => {
      const jsonText = JSON.stringify(
        {
          endpoint: "https://api.openai.com/v1/chat",
          docs: "https://platform.openai.com/docs",
        },
        null,
        2,
      );
      const { container } = render(<LinkifyText>{jsonText}</LinkifyText>);
      const links = getLinks(container);
      expect(links).toHaveLength(2);
    });
  });

  describe("long URLs", () => {
    it("should linkify a very long URL", () => {
      const longUrl =
        "https://example.com/very/long/path/" +
        Array.from({ length: 50 }, (_, i) => `segment${i}`).join("/") +
        "?key=value";
      const { container } = render(<LinkifyText>{longUrl}</LinkifyText>);
      const links = getLinks(container);
      expect(links).toHaveLength(1);
      expect(links[0].href).toContain("segment49");
    });

    it("should linkify URL with many query parameters", () => {
      const longUrl =
        "https://example.com/search?" +
        Array.from({ length: 20 }, (_, i) => `p${i}=v${i}`).join("&");
      const { container } = render(<LinkifyText>{longUrl}</LinkifyText>);
      expect(getLinks(container)).toHaveLength(1);
    });
  });

  describe("pre-check optimization", () => {
    it("should render plain text strings without wrapping in Linkify", () => {
      const { container } = render(
        <LinkifyText>{"No URLs here at all"}</LinkifyText>,
      );
      // The string should be returned directly — no extra wrapper elements
      expect(container.innerHTML).toBe("No URLs here at all");
    });

    it("should still process strings that contain http/https", () => {
      const { container } = render(
        <LinkifyText>{"Check https://example.com for info"}</LinkifyText>,
      );
      expect(getLinks(container)).toHaveLength(1);
    });
  });
});
