import { describe, expect, it } from "vitest";
import {
  cleanTerminalControls,
  extractOsc8Links,
  restoreOsc8Links,
  convertTerminalOutputToHtml,
  ansiConverter,
} from "./terminalOutput";

describe("cleanTerminalControls", () => {
  describe("cursor visibility controls", () => {
    it("should remove cursor show sequence (ESC[?25h)", () => {
      const input = "Hello\x1b[?25hWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor hide sequence (ESC[?25l)", () => {
      const input = "Hello\x1b[?25lWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor controls without ESC prefix", () => {
      const input = "Hello[?25h]World";
      expect(cleanTerminalControls(input)).toBe("Hello]World");
    });
  });

  describe("DEC private modes", () => {
    it("should remove DEC private mode set sequences", () => {
      const input = "Hello\x1b[?1049hWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove DEC private mode reset sequences", () => {
      const input = "Hello\x1b[?1049lWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove DEC modes with multiple parameters", () => {
      const input = "Hello\x1b[?1;2;3hWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });
  });

  describe("cursor position saves/restores", () => {
    it("should remove cursor save sequence (ESC[s)", () => {
      const input = "Hello\x1b[sWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor restore sequence (ESC[u)", () => {
      const input = "Hello\x1b[uWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove ESC7 and ESC8 sequences", () => {
      const input = "Hello\x1b7World\x1b8Test";
      expect(cleanTerminalControls(input)).toBe("HelloWorldTest");
    });
  });

  describe("erase commands", () => {
    it("should remove erase to end of line (ESC[K)", () => {
      const input = "Hello\x1b[KWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove erase with parameter (ESC[2K)", () => {
      const input = "Hello\x1b[2KWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove erase screen (ESC[J)", () => {
      const input = "Hello\x1b[JWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove erase screen with parameter (ESC[2J)", () => {
      const input = "Hello\x1b[2JWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });
  });

  describe("cursor movement", () => {
    it("should remove cursor up (ESC[nA)", () => {
      const input = "Hello\x1b[5AWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor down (ESC[nB)", () => {
      const input = "Hello\x1b[3BWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor forward (ESC[nC)", () => {
      const input = "Hello\x1b[10CWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor back (ESC[nD)", () => {
      const input = "Hello\x1b[2DWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor movement without number (ESC[A)", () => {
      const input = "Hello\x1b[AWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });
  });

  describe("cursor position", () => {
    it("should remove cursor position (ESC[n;mH)", () => {
      const input = "Hello\x1b[10;20HWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove cursor position with f terminator (ESC[n;mf)", () => {
      const input = "Hello\x1b[5;10fWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should remove simple home position (ESC[H)", () => {
      const input = "Hello\x1b[HWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });
  });

  describe("scroll region", () => {
    it("should remove scroll region (ESC[n;mr)", () => {
      const input = "Hello\x1b[1;24rWorld";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });
  });

  describe("orphaned SGR codes", () => {
    it("should remove orphaned SGR codes at start of line", () => {
      const input = "[0mHello World";
      expect(cleanTerminalControls(input)).toBe("Hello World");
    });

    it("should remove orphaned SGR codes after newline", () => {
      const input = "Hello\n[32mWorld";
      expect(cleanTerminalControls(input)).toBe("Hello\nWorld");
    });

    it("should remove orphaned SGR codes after whitespace", () => {
      const input = "Hello [1;32mWorld";
      expect(cleanTerminalControls(input)).toBe("Hello World");
    });

    it("should not remove SGR-like codes in the middle of text", () => {
      const input = "Array[0m is not valid";
      expect(cleanTerminalControls(input)).toBe("Array[0m is not valid");
    });
  });

  describe("empty line filtering", () => {
    it("should remove empty lines", () => {
      const input = "Hello\n\nWorld";
      expect(cleanTerminalControls(input)).toBe("Hello\nWorld");
    });

    it("should remove lines with only whitespace", () => {
      const input = "Hello\n   \nWorld";
      expect(cleanTerminalControls(input)).toBe("Hello\nWorld");
    });

    it("should preserve lines with actual content", () => {
      const input = "Hello\n  Content  \nWorld";
      expect(cleanTerminalControls(input)).toBe("Hello\n  Content  \nWorld");
    });
  });

  describe("complex terminal output", () => {
    it("should handle multiple control sequences", () => {
      const input = "\x1b[?25l\x1b[2J\x1b[HHello\x1b[5AWorld\x1b[K\x1b[?25h";
      expect(cleanTerminalControls(input)).toBe("HelloWorld");
    });

    it("should preserve ANSI color codes for ansi-to-html", () => {
      const input = "\x1b[32mGreen\x1b[0m Normal";
      expect(cleanTerminalControls(input)).toBe("\x1b[32mGreen\x1b[0m Normal");
    });
  });
});

describe("extractOsc8Links", () => {
  describe("basic link extraction", () => {
    it("should extract OSC 8 link with BEL terminator", () => {
      const input = "\x1b]8;;https://example.com\x07Click here\x1b]8;;\x07";
      const result = extractOsc8Links(input);

      expect(result.links.size).toBe(1);
      expect(result.processed).toBe("__OPIK_LINK_0__");

      const linkInfo = result.links.get("__OPIK_LINK_0__");
      expect(linkInfo).toEqual({
        url: "https://example.com",
        text: "Click here",
      });
    });

    it("should extract OSC 8 link with ST terminator", () => {
      const input = "\x1b]8;;https://example.com\x1b\\Click here\x1b]8;;\x1b\\";
      const result = extractOsc8Links(input);

      expect(result.links.size).toBe(1);
      expect(result.processed).toBe("__OPIK_LINK_0__");

      const linkInfo = result.links.get("__OPIK_LINK_0__");
      expect(linkInfo).toEqual({
        url: "https://example.com",
        text: "Click here",
      });
    });
  });

  describe("multiple links", () => {
    it("should extract multiple OSC 8 links", () => {
      const input =
        "Before \x1b]8;;https://a.com\x07Link A\x1b]8;;\x07 middle \x1b]8;;https://b.com\x07Link B\x1b]8;;\x07 after";
      const result = extractOsc8Links(input);

      expect(result.links.size).toBe(2);
      expect(result.processed).toBe(
        "Before __OPIK_LINK_0__ middle __OPIK_LINK_1__ after",
      );

      expect(result.links.get("__OPIK_LINK_0__")).toEqual({
        url: "https://a.com",
        text: "Link A",
      });
      expect(result.links.get("__OPIK_LINK_1__")).toEqual({
        url: "https://b.com",
        text: "Link B",
      });
    });
  });

  describe("links with params", () => {
    it("should handle OSC 8 links with parameters", () => {
      const input =
        "\x1b]8;id=myid;https://example.com\x07Click here\x1b]8;;\x07";
      const result = extractOsc8Links(input);

      expect(result.links.size).toBe(1);
      const linkInfo = result.links.get("__OPIK_LINK_0__");
      expect(linkInfo?.url).toBe("https://example.com");
    });
  });

  describe("no links", () => {
    it("should return original text when no OSC 8 links present", () => {
      const input = "Just regular text without links";
      const result = extractOsc8Links(input);

      expect(result.links.size).toBe(0);
      expect(result.processed).toBe(input);
    });

    it("should return empty string for empty input", () => {
      const result = extractOsc8Links("");

      expect(result.links.size).toBe(0);
      expect(result.processed).toBe("");
    });
  });

  describe("edge cases", () => {
    it("should handle links with special characters in URL", () => {
      const input =
        "\x1b]8;;https://example.com/path?query=value&other=test\x07Link\x1b]8;;\x07";
      const result = extractOsc8Links(input);

      const linkInfo = result.links.get("__OPIK_LINK_0__");
      expect(linkInfo?.url).toBe(
        "https://example.com/path?query=value&other=test",
      );
    });

    it("should handle links with multiline text", () => {
      const input = "\x1b]8;;https://example.com\x07Line 1\nLine 2\x1b]8;;\x07";
      const result = extractOsc8Links(input);

      const linkInfo = result.links.get("__OPIK_LINK_0__");
      expect(linkInfo?.text).toBe("Line 1\nLine 2");
    });
  });
});

describe("restoreOsc8Links", () => {
  describe("basic restoration", () => {
    it("should restore links from placeholders", () => {
      const links = new Map([
        ["__OPIK_LINK_0__", { url: "https://example.com", text: "Click here" }],
      ]);
      const html = "Before __OPIK_LINK_0__ after";

      const result = restoreOsc8Links(html, links);

      expect(result).toBe(
        'Before <a href="https://example.com" target="_blank" rel="noopener noreferrer" class="text-blue-400 underline hover:text-blue-300">Click here</a> after',
      );
    });

    it("should restore multiple links", () => {
      const links = new Map([
        ["__OPIK_LINK_0__", { url: "https://a.com", text: "A" }],
        ["__OPIK_LINK_1__", { url: "https://b.com", text: "B" }],
      ]);
      const html = "__OPIK_LINK_0__ and __OPIK_LINK_1__";

      const result = restoreOsc8Links(html, links);

      expect(result).toContain('href="https://a.com"');
      expect(result).toContain(">A</a>");
      expect(result).toContain('href="https://b.com"');
      expect(result).toContain(">B</a>");
    });
  });

  describe("HTML escaping", () => {
    it("should escape ampersands in URL", () => {
      const links = new Map([
        [
          "__OPIK_LINK_0__",
          { url: "https://example.com?a=1&b=2", text: "Link" },
        ],
      ]);
      const html = "__OPIK_LINK_0__";

      const result = restoreOsc8Links(html, links);

      expect(result).toContain('href="https://example.com?a=1&amp;b=2"');
    });

    it("should escape quotes in URL", () => {
      const links = new Map([
        [
          "__OPIK_LINK_0__",
          { url: 'https://example.com?q="test"', text: "Link" },
        ],
      ]);
      const html = "__OPIK_LINK_0__";

      const result = restoreOsc8Links(html, links);

      expect(result).toContain('href="https://example.com?q=&quot;test&quot;"');
    });

    it("should escape HTML entities in link text", () => {
      const links = new Map([
        [
          "__OPIK_LINK_0__",
          { url: "https://example.com", text: "<script>alert('xss')</script>" },
        ],
      ]);
      const html = "__OPIK_LINK_0__";

      const result = restoreOsc8Links(html, links);

      expect(result).toContain("&lt;script&gt;alert('xss')&lt;/script&gt;</a>");
      expect(result).not.toContain("<script>");
    });

    it("should escape ampersands in link text", () => {
      const links = new Map([
        [
          "__OPIK_LINK_0__",
          { url: "https://example.com", text: "Tom & Jerry" },
        ],
      ]);
      const html = "__OPIK_LINK_0__";

      const result = restoreOsc8Links(html, links);

      expect(result).toContain(">Tom &amp; Jerry</a>");
    });
  });

  describe("empty cases", () => {
    it("should return original HTML when no links map is empty", () => {
      const links = new Map<string, { url: string; text: string }>();
      const html = "Just regular HTML content";

      const result = restoreOsc8Links(html, links);

      expect(result).toBe(html);
    });
  });

  describe("link attributes", () => {
    it("should include correct link attributes", () => {
      const links = new Map([
        ["__OPIK_LINK_0__", { url: "https://example.com", text: "Test" }],
      ]);
      const html = "__OPIK_LINK_0__";

      const result = restoreOsc8Links(html, links);

      expect(result).toContain('target="_blank"');
      expect(result).toContain('rel="noopener noreferrer"');
      expect(result).toContain(
        'class="text-blue-400 underline hover:text-blue-300"',
      );
    });
  });
});

describe("ansiConverter", () => {
  describe("basic ANSI codes", () => {
    it("should convert red text", () => {
      const input = "\x1b[31mRed text\x1b[0m";
      const result = ansiConverter.toHtml(input);

      expect(result).toContain("Red text");
      expect(result).toContain("#dc2626"); // red-600
    });

    it("should convert green text", () => {
      const input = "\x1b[32mGreen text\x1b[0m";
      const result = ansiConverter.toHtml(input);

      expect(result).toContain("Green text");
      expect(result).toContain("#16a34a"); // green-600
    });

    it("should convert bright blue text", () => {
      const input = "\x1b[94mBright blue\x1b[0m";
      const result = ansiConverter.toHtml(input);

      expect(result).toContain("Bright blue");
      expect(result).toContain("#3b82f6"); // blue-500
    });
  });

  describe("XML escaping", () => {
    it("should escape HTML entities", () => {
      const input = "<script>alert('xss')</script>";
      const result = ansiConverter.toHtml(input);

      expect(result).not.toContain("<script>");
      expect(result).toContain("&lt;script&gt;");
    });
  });

  describe("newlines", () => {
    it("should preserve newlines", () => {
      const input = "Line 1\nLine 2\nLine 3";
      const result = ansiConverter.toHtml(input);

      expect(result).toContain("<br/>");
    });
  });
});

describe("convertTerminalOutputToHtml", () => {
  describe("empty input", () => {
    it("should return empty string for empty input", () => {
      expect(convertTerminalOutputToHtml("")).toBe("");
    });

    it("should return empty string for null-like input", () => {
      expect(convertTerminalOutputToHtml(null as unknown as string)).toBe("");
      expect(convertTerminalOutputToHtml(undefined as unknown as string)).toBe(
        "",
      );
    });
  });

  describe("plain text", () => {
    it("should handle plain text without ANSI codes", () => {
      const result = convertTerminalOutputToHtml("Hello World");
      expect(result).toContain("Hello World");
    });
  });

  describe("ANSI colors", () => {
    it("should convert ANSI color codes to HTML", () => {
      const input = "\x1b[31mError:\x1b[0m Something went wrong";
      const result = convertTerminalOutputToHtml(input);

      expect(result).toContain("Error:");
      expect(result).toContain("#dc2626"); // red-600
    });
  });

  describe("terminal controls with colors", () => {
    it("should clean terminal controls and preserve colors", () => {
      const input = "\x1b[?25l\x1b[32mSuccess\x1b[0m message\x1b[?25h";
      const result = convertTerminalOutputToHtml(input);

      expect(result).toContain("Success");
      expect(result).toContain("#16a34a"); // green-600
      expect(result).not.toContain("[?25l");
      expect(result).not.toContain("[?25h");
    });
  });

  describe("OSC 8 links with colors", () => {
    it("should handle OSC 8 links in colored output", () => {
      const input =
        "Check \x1b]8;;https://docs.example.com\x07documentation\x1b]8;;\x07 for more info";
      const result = convertTerminalOutputToHtml(input);

      expect(result).toContain('href="https://docs.example.com"');
      expect(result).toContain(">documentation</a>");
      expect(result).toContain("for more info");
    });
  });

  describe("complex Rich output", () => {
    it("should handle typical Rich library output", () => {
      const input = [
        "\x1b[?25l", // hide cursor
        "\x1b[32m✓\x1b[0m Task completed",
        "\n",
        "\x1b[31m✗\x1b[0m Task failed",
        "\x1b[?25h", // show cursor
      ].join("");

      const result = convertTerminalOutputToHtml(input);

      // Note: ansi-to-html escapes XML entities, so ✓ becomes &#x2713; and ✗ becomes &#x2717;
      expect(result).toContain("&#x2713;"); // checkmark (escaped)
      expect(result).toContain("#16a34a"); // green for checkmark
      expect(result).toContain("&#x2717;"); // X mark (escaped)
      expect(result).toContain("#dc2626"); // red for X
      expect(result).not.toContain("?25");
    });
  });

  describe("integration", () => {
    it("should handle full pipeline: controls, links, and colors", () => {
      const input = [
        "\x1b[?25l\x1b[2J\x1b[H", // terminal setup
        "\x1b[1;34mWelcome\x1b[0m to the app\n",
        "Visit \x1b]8;;https://example.com\x07our site\x1b]8;;\x07 for help\n",
        "\x1b[32mStatus:\x1b[0m OK",
        "\x1b[?25h", // show cursor
      ].join("");

      const result = convertTerminalOutputToHtml(input);

      // Check colors are preserved
      expect(result).toContain("#2563eb"); // blue-600 for Welcome
      expect(result).toContain("#16a34a"); // green-600 for Status

      // Check link is converted
      expect(result).toContain('href="https://example.com"');
      expect(result).toContain(">our site</a>");

      // Check terminal controls are removed
      expect(result).not.toContain("?25");
      expect(result).not.toContain("[2J");
      expect(result).not.toContain("[H");
    });
  });
});
