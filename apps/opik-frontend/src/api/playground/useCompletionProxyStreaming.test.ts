import { describe, expect, it } from "vitest";
import {
  processSSEChunk,
  pythonProxyErrorMessage,
} from "./useCompletionProxyStreaming";

describe("processSSEChunk", () => {
  describe("basic line processing", () => {
    it("should process a single complete line", () => {
      const result = processSSEChunk(
        'data: {"choices":[{"delta":{"content":"hello"}}]}\n',
        "",
      );
      expect(result.lines).toEqual([
        'data: {"choices":[{"delta":{"content":"hello"}}]}',
      ]);
      expect(result.newBuffer).toBe("");
    });

    it("should process multiple complete lines", () => {
      const result = processSSEChunk(
        'data: {"choices":[{"delta":{"content":"hello"}}]}\ndata: {"choices":[{"delta":{"content":" world"}}]}\n',
        "",
      );
      expect(result.lines).toEqual([
        'data: {"choices":[{"delta":{"content":"hello"}}]}',
        'data: {"choices":[{"delta":{"content":" world"}}]}',
      ]);
      expect(result.newBuffer).toBe("");
    });

    it("should skip empty lines", () => {
      const result = processSSEChunk(
        'data: {"content":"a"}\n\n\ndata: {"content":"b"}\n',
        "",
      );
      expect(result.lines).toEqual([
        'data: {"content":"a"}',
        'data: {"content":"b"}',
      ]);
      expect(result.newBuffer).toBe("");
    });
  });

  describe("buffering incomplete lines", () => {
    it("should buffer an incomplete line (no trailing newline)", () => {
      const result = processSSEChunk(
        'data: {"choices":[{"delta":{"content":"hello"}}]}\ndata: {"choi',
        "",
      );
      expect(result.lines).toEqual([
        'data: {"choices":[{"delta":{"content":"hello"}}]}',
      ]);
      expect(result.newBuffer).toBe('data: {"choi');
    });

    it("should prepend buffer to next chunk and complete the line", () => {
      // First chunk ends with incomplete line
      const result1 = processSSEChunk(
        'data: {"choices":[{"delta":{"content":"hello"}}]}\ndata: {"choi',
        "",
      );
      expect(result1.newBuffer).toBe('data: {"choi');

      // Second chunk completes the line
      const result2 = processSSEChunk(
        'ces":[{"delta":{"content":","}}]}\n',
        result1.newBuffer,
      );
      expect(result2.lines).toEqual([
        'data: {"choices":[{"delta":{"content":","}}]}',
      ]);
      expect(result2.newBuffer).toBe("");
    });

    it("should handle line split across multiple chunks", () => {
      // Simulate a JSON line split across 3 chunks
      const result1 = processSSEChunk('data: {"choices":[{"del', "");
      expect(result1.lines).toEqual([]);
      expect(result1.newBuffer).toBe('data: {"choices":[{"del');

      const result2 = processSSEChunk(
        'ta":{"content":"test',
        result1.newBuffer,
      );
      expect(result2.lines).toEqual([]);
      expect(result2.newBuffer).toBe(
        'data: {"choices":[{"delta":{"content":"test',
      );

      const result3 = processSSEChunk('"}}]}\n', result2.newBuffer);
      expect(result3.lines).toEqual([
        'data: {"choices":[{"delta":{"content":"test"}}]}',
      ]);
      expect(result3.newBuffer).toBe("");
    });
  });

  describe("edge cases", () => {
    it("should handle empty chunk with buffer", () => {
      const result = processSSEChunk("", 'data: {"partial":');
      expect(result.lines).toEqual([]);
      expect(result.newBuffer).toBe('data: {"partial":');
    });

    it("should handle chunk that is only newlines", () => {
      const result = processSSEChunk("\n\n\n", "");
      expect(result.lines).toEqual([]);
      expect(result.newBuffer).toBe("");
    });

    it("should handle buffer that becomes complete with newline chunk", () => {
      const result = processSSEChunk(
        "\n",
        'data: {"choices":[{"delta":{"content":"x"}}]}',
      );
      expect(result.lines).toEqual([
        'data: {"choices":[{"delta":{"content":"x"}}]}',
      ]);
      expect(result.newBuffer).toBe("");
    });

    it("should handle whitespace-only lines (should be filtered)", () => {
      const result = processSSEChunk("   \n\t\n", "");
      expect(result.lines).toEqual([]);
      expect(result.newBuffer).toBe("");
    });

    it("should handle chunk ending exactly at newline", () => {
      const result = processSSEChunk('data: {"content":"a"}\n', "");
      expect(result.lines).toEqual(['data: {"content":"a"}']);
      expect(result.newBuffer).toBe("");
    });
  });

  describe("real-world scenarios", () => {
    it("should preserve content when SSE message is split mid-JSON", () => {
      const chunk1 =
        'data: {"choices":[{"delta":{"content":"hello"}}]}\ndata: {"choi';
      const chunk2 = 'ces":[{"delta":{"content":","}}]}\n';

      const result1 = processSSEChunk(chunk1, "");
      expect(result1.lines).toEqual([
        'data: {"choices":[{"delta":{"content":"hello"}}]}',
      ]);
      expect(result1.newBuffer).toBe('data: {"choi');

      const result2 = processSSEChunk(chunk2, result1.newBuffer);
      expect(result2.lines).toEqual([
        'data: {"choices":[{"delta":{"content":","}}]}',
      ]);
      expect(result2.newBuffer).toBe("");
    });

    it("should handle streaming with usage info at the end", () => {
      const chunks = [
        'data: {"choices":[{"delta":{"content":"Hi"}}]}\ndata: {"choi',
        'ces":[{"delta":{"content":"!"}}]}\n',
        'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}\n',
      ];

      let buffer = "";
      const allLines: string[] = [];

      for (const chunk of chunks) {
        const result = processSSEChunk(chunk, buffer);
        allLines.push(...result.lines);
        buffer = result.newBuffer;
      }

      expect(allLines).toEqual([
        'data: {"choices":[{"delta":{"content":"Hi"}}]}',
        'data: {"choices":[{"delta":{"content":"!"}}]}',
        'data: {"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2}}',
      ]);
      expect(buffer).toBe("");
    });

    it("should handle [DONE] signal", () => {
      const result = processSSEChunk("data: [DONE]\n", "");
      expect(result.lines).toEqual(["data: [DONE]"]);
      expect(result.newBuffer).toBe("");
    });

    it("should preserve comma in JSON schema output - qwen model scenario", () => {
      const chunks = [
        'data: {"choices":[{"delta":{"content":"[\\"wedding surprise reactions|0.86\\""}}]}\n',
        'data: {"choices":[{"delta":{"content":","}}]}\ndata: {"ch',
        'oices":[{"delta":{"content":" \\"beach sunset|0.92\\"]"}}]}\n',
      ];

      let buffer = "";
      const allLines: string[] = [];

      for (const chunk of chunks) {
        const result = processSSEChunk(chunk, buffer);
        allLines.push(...result.lines);
        buffer = result.newBuffer;
      }

      expect(allLines).toHaveLength(3);
      expect(allLines[0]).toContain("wedding surprise reactions|0.86");
      expect(allLines[1]).toContain(",");
      expect(allLines[2]).toContain("beach sunset|0.92");
      expect(buffer).toBe("");
    });

    it("should handle JSON with pipe-separated values split across many chunks", () => {
      const chunks = [
        'data: {"choices":[{"delta":{"content":"wedding surprise reactions|0.86"}}]}\n',
        'data: {"choices":[{"delta":{"content":","}}]}\ndata: {"choices":[{"del',
        'ta":{"content":" beach|0.75"}}]}\n',
      ];

      let buffer = "";
      const allLines: string[] = [];

      for (const chunk of chunks) {
        const result = processSSEChunk(chunk, buffer);
        allLines.push(...result.lines);
        buffer = result.newBuffer;
      }

      expect(allLines).toHaveLength(3);
      expect(allLines[0]).toContain("wedding surprise reactions|0.86");
      expect(allLines[1]).toContain(",");
      expect(allLines[2]).toContain("beach|0.75");
      expect(buffer).toBe("");

      const contentParts = allLines.map((line) => {
        const match = line.match(/"content":"([^"]*)"/);
        return match ? match[1] : "";
      });
      const fullContent = contentParts.join("");

      expect(fullContent).toBe("wedding surprise reactions|0.86, beach|0.75");
    });
  });
});

describe("pythonProxyErrorMessage", () => {
  // The two shapes the proxy actually sends. Reading only the first left the
  // second assigning the wrapper object, which reached the user as
  // "Run failed: [object Object]".
  it("should read the message nested under detail", () => {
    expect(pythonProxyErrorMessage({ detail: "Invalid API key" })).toBe(
      "Invalid API key",
    );
  });

  it("should read the message under error", () => {
    expect(pythonProxyErrorMessage({ error: "Rate limit exceeded" })).toBe(
      "Rate limit exceeded",
    );
  });

  it("should take a detail that is already a message", () => {
    expect(pythonProxyErrorMessage("Upstream timed out")).toBe(
      "Upstream timed out",
    );
  });

  // A `detail` at all is what says the run failed; the text only describes it.
  // Returning the blank string would leave the error falsy, and a failed run
  // with partially streamed content would then render as a successful one.
  it.each([
    ["nothing", undefined],
    ["null", null],
    ["an unrecognised shape", { code: 500 }],
    ["a nested object", { detail: { deeper: "value" } }],
    ["an empty detail", { detail: "" }],
    ["a whitespace-only detail", { detail: "   " }],
    ["an empty error", { error: "" }],
    ["an empty string", ""],
  ])("should fall back to a generic message given %s", (_label, detail) => {
    expect(pythonProxyErrorMessage(detail)).toBe("Python proxy error");
  });

  it("should never return a falsy message, whatever the proxy sends", () => {
    const shapes = [undefined, null, "", "   ", { detail: "" }, { error: "" }];

    for (const shape of shapes) {
      expect(pythonProxyErrorMessage(shape)).toBeTruthy();
    }
  });

  it("should prefer a usable message over a blank one earlier in the chain", () => {
    expect(
      pythonProxyErrorMessage({ error: "", detail: "Invalid API key" }),
    ).toBe("Invalid API key");
  });

  it("should trim the message it returns", () => {
    expect(pythonProxyErrorMessage({ detail: "  Invalid API key  " })).toBe(
      "Invalid API key",
    );
  });

  it("should never hand back a value that renders as [object Object]", () => {
    const shapes = [
      { detail: "a" },
      { error: "b" },
      "c",
      { code: 1 },
      null,
      undefined,
    ];

    for (const shape of shapes) {
      expect(String(pythonProxyErrorMessage(shape))).not.toContain(
        "[object Object]",
      );
    }
  });
});
