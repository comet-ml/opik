import { describe, expect, it } from "vitest";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { getValueByJSONPath } from "@/lib/utils";
import {
  DEFAULT_ANNOTATOR_FIELDS,
  buildFieldsTree,
  getAnnotatorFields,
  getFieldKind,
  getFieldScores,
  getMessagePreview,
  extractMessageText,
  labelFromPath,
  messagePreviewText,
  toAnnotatorPath,
} from "./annotatorViewConfig";

const scores = [
  { name: "accuracy", value: 0.8, source: FEEDBACK_SCORE_TYPE.online_scoring },
  {
    name: "hallucination",
    value: 0,
    source: FEEDBACK_SCORE_TYPE.online_scoring,
  },
];

describe("getFieldKind", () => {
  it.each([
    ["attachments", "attachments"],
    ["feedback_scores", "feedback_scores"],
    ["feedback_scores.accuracy", "feedback_scores"],
    ["input.messages[-1].content", "value"],
    ["metadata", "value"],
  ])("%s is %s", (path, kind) => {
    expect(getFieldKind(path)).toBe(kind);
  });
});

describe("getFieldScores", () => {
  it("returns every score for the root path", () => {
    expect(getFieldScores("feedback_scores", scores)).toEqual(scores);
  });

  it("returns only the named score", () => {
    expect(getFieldScores("feedback_scores.accuracy", scores)).toEqual([
      scores[0],
    ]);
  });

  it("returns nothing when the named score is missing", () => {
    expect(getFieldScores("feedback_scores.tone", scores)).toEqual([]);
  });
});

describe("labelFromPath", () => {
  it.each([
    ["input", "Input"],
    ["attachments", "Attachments"],
    ["feedback_scores", "Feedback scores"],
    ["output.answer", "answer"],
    ["input.messages[-1].content", "content"],
    ["feedback_scores.accuracy", "accuracy"],
  ])("%s → %s", (path, label) => {
    expect(labelFromPath(path)).toBe(label);
  });
});

describe("buildFieldsTree", () => {
  it("keys feedback scores by name and adds attachments", () => {
    expect(
      buildFieldsTree({ input: { q: 1 }, output: { a: 2 } }, scores),
    ).toEqual({
      input: { q: 1 },
      output: { a: 2 },
      metadata: {},
      attachments: "All attachments",
      feedback_scores: { accuracy: 0.8, hallucination: 0 },
    });
  });
});

describe("getAnnotatorFields", () => {
  it("defaults to today's blocks when no fields are saved", () => {
    expect(getAnnotatorFields({})).toBe(DEFAULT_ANNOTATOR_FIELDS);
    expect(getAnnotatorFields({ fields: [] })).toEqual([]);
  });
});

describe("messagePreviewText", () => {
  it.each([
    ["<p>Hello <b>there</b></p><p>friend</p>", "Hello there friend"],
    ["Use Map<String, Integer> here", "Use Map<String, Integer> here"],
    ["if a < b and c > d", "if a < b and c > d"],
    ["List<String>", "List<String>"],
  ])("%s → %s", (input, expected) => {
    expect(messagePreviewText(input)).toBe(expected);
  });
});

describe("extractMessageText", () => {
  const parts = [
    { type: "text", text: "Hi, what is in this image?" },
    { type: "image_url", image_url: { url: "https://example.com/cat.png" } },
  ];

  it("joins the text of content parts", () => {
    expect(extractMessageText(parts)).toBe("Hi, what is in this image?");
    expect(messagePreviewText(parts)).toBe("Hi, what is in this image?");
  });

  it("keeps values without text parts", () => {
    expect(extractMessageText("plain")).toBe("plain");
    expect(extractMessageText([parts[1]])).toEqual([parts[1]]);
  });
});

describe("getMessagePreview", () => {
  const input = {
    messages: [
      { role: "system", content: "sys" },
      { role: "user", content: "Last question" },
    ],
  };

  it("reads the configured path", () => {
    expect(getMessagePreview(input, "input", "messages[-1].content")).toBe(
      "Last question",
    );
  });

  it("falls back to the automatic preview when the path does not resolve", () => {
    expect(getMessagePreview(input, "input", "missing.path")).toBe(
      getMessagePreview(input, "input"),
    );
    expect(getMessagePreview(input, "input", "missing.path")).not.toBe("");
  });

  it("falls back on input cut off by truncation", () => {
    const truncated = JSON.stringify(input).slice(0, 40) as unknown as object;
    expect(
      getMessagePreview(truncated, "input", "messages[-1].content"),
    ).not.toBe("");
  });
});

describe("toAnnotatorPath", () => {
  const tree = {
    input: { messages: [{ content: "hi" }] },
    metadata: { "gen_ai.request.model": "gpt-4o", "a[0]": 1 },
    feedback_scores: { "g-eval.coherence": 0.8 },
  };

  it.each([
    ["input.messages[0].content", "input.messages[0].content"],
    ["metadata.gen_ai.request.model", 'metadata["gen_ai.request.model"]'],
    ["metadata.a[0]", 'metadata["a[0]"]'],
    ["feedback_scores.g-eval.coherence", 'feedback_scores["g-eval.coherence"]'],
  ])("%s → %s", (treePath, path) => {
    expect(toAnnotatorPath(tree, treePath)).toBe(path);
  });

  it("resolves the quoted paths", () => {
    expect(
      getValueByJSONPath(
        tree,
        toAnnotatorPath(tree, "metadata.gen_ai.request.model"),
      ),
    ).toBe("gpt-4o");
  });

  it("finds a score whose name has a dot", () => {
    const dotted = [
      { name: "g-eval.coherence", value: 0.8, source: FEEDBACK_SCORE_TYPE.sdk },
    ];
    expect(
      getFieldScores('feedback_scores["g-eval.coherence"]', dotted),
    ).toEqual(dotted);
    expect(getFieldScores("feedback_scores.g-eval.coherence", dotted)).toEqual(
      dotted,
    );
  });
});
