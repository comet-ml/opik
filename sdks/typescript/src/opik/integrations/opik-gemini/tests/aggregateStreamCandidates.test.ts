import { describe, expect, it } from "vitest";
import { aggregateStreamCandidates } from "../src/parsers";

const chunk = (
  parts: Array<Record<string, unknown>>,
  finishReason?: string
) => ({
  candidates: [{ content: { role: "model", parts }, finishReason }],
});

const call = (city: string) => ({
  functionCall: { name: "get_weather", args: { city } },
});

describe("aggregateStreamCandidates", () => {
  it("joins the text of every chunk into one part", () => {
    const result = aggregateStreamCandidates([
      chunk([{ text: "Hel" }]),
      chunk([{ text: "lo" }], "STOP"),
    ]);

    expect(result.candidates?.[0].content?.parts).toEqual([{ text: "Hello" }]);
    expect(result.candidates?.[0].finishReason).toBe("STOP");
  });

  it("keeps function calls from every chunk", () => {
    const result = aggregateStreamCandidates([
      chunk([call("Paris")]),
      chunk([call("Tokyo")], "STOP"),
    ]);

    expect(result.candidates?.[0].content?.parts).toEqual([
      call("Paris"),
      call("Tokyo"),
    ]);
    expect(result.functionCalls.map((c) => c.args)).toEqual([
      { city: "Paris" },
      { city: "Tokyo" },
    ]);
  });

  it("keeps text and a following function call as separate parts", () => {
    const result = aggregateStreamCandidates([
      chunk([{ text: "Let me check. " }]),
      chunk([call("Paris")], "STOP"),
    ]);

    expect(result.candidates?.[0].content?.parts).toEqual([
      { text: "Let me check. " },
      call("Paris"),
    ]);
  });

  it("keeps the text when the last chunk has no candidates", () => {
    const result = aggregateStreamCandidates([
      chunk([{ text: "Hello" }], "STOP"),
      { usageMetadata: { promptTokenCount: 3 } },
    ]);

    expect(result.candidates?.[0].content?.parts).toEqual([{ text: "Hello" }]);
  });

  it("returns no candidates for a stream without any", () => {
    expect(aggregateStreamCandidates([{ usageMetadata: {} }])).toEqual({
      functionCalls: [],
    });
  });
});
