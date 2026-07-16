import { describe, it, expect } from "vitest";
import {
  convertOptimizationVariableFormat,
  checkIsTestSuite,
  getOptimizationDefaultConfigByProvider,
  extractKwargsKeysFromPython,
  extractRequiredScoreParams,
} from "./optimizations";
import {
  Experiment,
  EXPERIMENT_TYPE,
  EVALUATION_METHOD,
} from "@/types/datasets";
import {
  COMPOSED_PROVIDER_TYPE,
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
} from "@/types/providers";

const makeExperiment = (overrides: Partial<Experiment> = {}): Experiment => ({
  id: "exp-1",
  dataset_id: "ds-1",
  dataset_name: "test-dataset",
  type: EXPERIMENT_TYPE.REGULAR,
  status: "completed",
  name: "test-experiment",
  trace_count: 10,
  created_at: "2024-01-01T00:00:00Z",
  last_updated_at: "2024-01-01T00:00:00Z",
  ...overrides,
});

describe("checkIsTestSuite", () => {
  it("should return true when any experiment has evaluation_method 'test_suite'", () => {
    const experiments = [
      makeExperiment({ evaluation_method: EVALUATION_METHOD.DATASET }),
      makeExperiment({
        evaluation_method: EVALUATION_METHOD.TEST_SUITE,
      }),
    ];
    expect(checkIsTestSuite(experiments)).toBe(true);
  });

  it("should return false when no experiment has evaluation_method 'test_suite'", () => {
    const experiments = [
      makeExperiment({ evaluation_method: EVALUATION_METHOD.DATASET }),
      makeExperiment({
        evaluation_method: "unknown" as EVALUATION_METHOD,
      }),
    ];
    expect(checkIsTestSuite(experiments)).toBe(false);
  });

  it("should return false for empty array", () => {
    expect(checkIsTestSuite([])).toBe(false);
  });

  it("should return false when evaluation_method is undefined and no experiment_scores", () => {
    const experiments = [makeExperiment(), makeExperiment()];
    expect(checkIsTestSuite(experiments)).toBe(false);
  });

  it("should return true when all experiments have evaluation_method 'test_suite'", () => {
    const experiments = [
      makeExperiment({
        evaluation_method: EVALUATION_METHOD.TEST_SUITE,
      }),
      makeExperiment({
        evaluation_method: EVALUATION_METHOD.TEST_SUITE,
      }),
    ];
    expect(checkIsTestSuite(experiments)).toBe(true);
  });

  it("should return false when experiment has scores but evaluation_method is not test_suite", () => {
    const experiments = [
      makeExperiment({
        evaluation_method: EVALUATION_METHOD.DATASET,
        experiment_scores: [{ name: "pass_rate", value: 0.8 }],
      }),
    ];
    expect(checkIsTestSuite(experiments)).toBe(false);
  });
});

describe("convertOptimizationVariableFormat", () => {
  describe("string content", () => {
    it("should convert single variable from {var} to {{var}}", () => {
      const input = "Answer the {question}";
      const expected = "Answer the {{question}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should convert multiple variables in a string", () => {
      const input = "Use {context} to answer {question}";
      const expected = "Use {{context}} to answer {{question}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables at the start of string", () => {
      const input = "{question} is the question";
      const expected = "{{question}} is the question";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables at the end of string", () => {
      const input = "Answer this: {question}";
      const expected = "Answer this: {{question}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle multiple consecutive variables", () => {
      const input = "{var1}{var2}{var3}";
      const expected = "{{var1}}{{var2}}{{var3}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with underscores", () => {
      const input = "Use {user_input} and {system_context}";
      const expected = "Use {{user_input}} and {{system_context}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with numbers", () => {
      const input = "Item {item1} and {item2}";
      const expected = "Item {{item1}} and {{item2}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should not convert already-converted variables", () => {
      const input = "Already converted {{variable}}";
      const expected = "Already converted {{variable}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle mixed converted and unconverted variables", () => {
      const input = "Convert {this} but not {{that}}";
      const expected = "Convert {{this}} but not {{that}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle empty string", () => {
      const input = "";
      const expected = "";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle string with no variables", () => {
      const input = "No variables here";
      const expected = "No variables here";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle string with only curly braces (no content)", () => {
      const input = "Empty braces {} should not be converted";
      const expected = "Empty braces {} should not be converted";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with dots (object notation)", () => {
      const input = "Access {user.name} and {data.value}";
      const expected = "Access {{user.name}} and {{data.value}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle variables with hyphens", () => {
      const input = "Use {user-input} here";
      const expected = "Use {{user-input}} here";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle complex real-world example", () => {
      const input =
        "Given the context: {context}\n\nAnswer the following question: {question}\n\nProvide a detailed response.";
      const expected =
        "Given the context: {{context}}\n\nAnswer the following question: {{question}}\n\nProvide a detailed response.";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });
  });

  describe("multimodal content (array)", () => {
    it("should convert text parts in multimodal content", () => {
      const input = [
        { type: "text", text: "Describe {image}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/img.jpg" },
        },
      ];
      const expected = [
        { type: "text", text: "Describe {{image}}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/img.jpg" },
        },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle multiple text parts with variables", () => {
      const input = [
        { type: "text", text: "First {var1}" },
        { type: "text", text: "Second {var2}" },
      ];
      const expected = [
        { type: "text", text: "First {{var1}}" },
        { type: "text", text: "Second {{var2}}" },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should preserve non-text parts unchanged", () => {
      const input = [
        {
          type: "image_url",
          image_url: { url: "https://example.com/img.jpg" },
        },
        {
          type: "video_url",
          video_url: { url: "https://example.com/vid.mp4" },
        },
        {
          type: "audio_url",
          audio_url: { url: "https://example.com/audio.mp3" },
        },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(input);
    });

    it("should handle mixed content with text and media", () => {
      const input = [
        { type: "text", text: "Analyze {data}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/chart.png" },
        },
        { type: "text", text: "Provide {analysis}" },
      ];
      const expected = [
        { type: "text", text: "Analyze {{data}}" },
        {
          type: "image_url",
          image_url: { url: "https://example.com/chart.png" },
        },
        { type: "text", text: "Provide {{analysis}}" },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle empty array", () => {
      const input: unknown[] = [];
      expect(convertOptimizationVariableFormat(input)).toEqual([]);
    });

    it("should handle array with text part without variables", () => {
      const input = [{ type: "text", text: "No variables here" }];
      const expected = [{ type: "text", text: "No variables here" }];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle text part with already-converted variables", () => {
      const input = [{ type: "text", text: "Already {{converted}}" }];
      const result = convertOptimizationVariableFormat(input) as Array<{
        type: string;
        text: string;
      }>;
      expect(result).toHaveLength(1);
      expect(result[0].type).toBe("text");
      expect(result[0].text).toBe("Already {{converted}}");
    });

    it("should preserve additional properties on text parts", () => {
      const input = [
        {
          type: "text",
          text: "Convert {this}",
          customProp: "value",
          anotherProp: 123,
        },
      ];
      const expected = [
        {
          type: "text",
          text: "Convert {{this}}",
          customProp: "value",
          anotherProp: 123,
        },
      ];
      expect(convertOptimizationVariableFormat(input)).toEqual(expected);
    });

    it("should handle malformed parts gracefully", () => {
      const input = [
        { type: "text", text: "Valid {var}" },
        { type: "text" }, // Missing text property
        null,
        undefined,
        { type: "other", content: "something" },
      ];
      const result = convertOptimizationVariableFormat(input) as unknown[];
      expect(result[0]).toEqual({ type: "text", text: "Valid {{var}}" });
      expect(result[1]).toEqual({ type: "text" });
      expect(result[2]).toBeNull();
      expect(result[3]).toBeUndefined();
      expect(result[4]).toEqual({ type: "other", content: "something" });
    });
  });

  describe("other types", () => {
    it("should return numbers unchanged", () => {
      const input = 123;
      expect(convertOptimizationVariableFormat(input)).toBe(123);
    });

    it("should return booleans unchanged", () => {
      expect(convertOptimizationVariableFormat(true)).toBe(true);
      expect(convertOptimizationVariableFormat(false)).toBe(false);
    });

    it("should return null unchanged", () => {
      expect(convertOptimizationVariableFormat(null)).toBeNull();
    });

    it("should return undefined unchanged", () => {
      expect(convertOptimizationVariableFormat(undefined)).toBeUndefined();
    });

    it("should return objects (non-array) unchanged", () => {
      const input = { key: "value", nested: { prop: "test" } };
      expect(convertOptimizationVariableFormat(input)).toEqual(input);
    });
  });

  describe("edge cases", () => {
    it("should handle nested braces correctly", () => {
      const input = "Code: {{nested {var} inside}}";
      // Should only convert the inner {var}
      const expected = "Code: {{nested {{var}} inside}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle special characters inside variables", () => {
      const input = "Use {var_with-special.chars}";
      const expected = "Use {{var_with-special.chars}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle very long variable names", () => {
      const longVarName = "a".repeat(100);
      const input = `Use {${longVarName}}`;
      const expected = `Use {{${longVarName}}}`;
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle strings with only variables", () => {
      const input = "{var}";
      const expected = "{{var}}";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle unicode characters in text", () => {
      const input = "Translate {text} to 中文 and {emoji} 🎉";
      const expected = "Translate {{text}} to 中文 and {{emoji}} 🎉";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });

    it("should handle newlines and tabs in text", () => {
      const input = "Line 1 {var1}\n\tLine 2 {var2}\r\nLine 3";
      const expected = "Line 1 {{var1}}\n\tLine 2 {{var2}}\r\nLine 3";
      expect(convertOptimizationVariableFormat(input)).toBe(expected);
    });
  });
});

describe("extractKwargsKeysFromPython", () => {
  it("does NOT treat a default-less kwargs.get(...) as a required column", () => {
    // `.get()` is missing-safe (returns None) and the editor helper copy
    // recommends it as the safe accessor, so it must never hard-block submit
    // (OPIK-7172 self-review fix).
    const code = `
def score(self, output, **kwargs):
    label = kwargs.get("label")
    return label
`;
    expect(extractKwargsKeysFromPython(code)).toEqual([]);
  });

  it("does NOT treat kwargs.get(x, default) as a required column", () => {
    // A supplied default means the metric tolerates a missing column, so it
    // must never hard-block submit (OPIK-7172 review fix).
    const code = `
def score(self, output, **kwargs):
    label = kwargs.get("label", "")
    other = kwargs.get('category', None)
    return label, other
`;
    expect(extractKwargsKeysFromPython(code)).toEqual([]);
  });

  it("returns only the required key when default-ful and required mix", () => {
    const code = `
def score(self, output, **kwargs):
    a = kwargs.get("optional_col", "fallback")
    b = kwargs["required_col"]
    return a, b
`;
    expect(extractKwargsKeysFromPython(code)).toEqual(["required_col"]);
  });

  it("extracts a kwargs[...] subscript key", () => {
    const code = `
def score(self, output, **kwargs):
    return kwargs["expected_value"]
`;
    expect(extractKwargsKeysFromPython(code)).toEqual(["expected_value"]);
  });

  it("extracts multiple distinct subscript keys, de-duplicated", () => {
    // Only subscript accesses are required; the default-less `.get()` on
    // `category` is missing-safe and must not be collected.
    const code = `
def score(self, output, **kwargs):
    a = kwargs.get("category")
    b = kwargs["reference"]
    c = kwargs["reference"]
    d = kwargs['expected']
    return a, b, c, d
`;
    expect(extractKwargsKeysFromPython(code).sort()).toEqual([
      "expected",
      "reference",
    ]);
  });

  it("never treats 'output' as a required dataset column", () => {
    // `output` is always injected by the backend, so even a literal
    // `kwargs.get("output")` access must not be flagged as a missing column.
    const code = `kwargs.get("output")`;
    expect(extractKwargsKeysFromPython(code)).toEqual([]);
  });

  it("returns an empty list for code with no kwargs access", () => {
    const code = `
def score(self, output):
    return output
`;
    expect(extractKwargsKeysFromPython(code)).toEqual([]);
  });

  it("returns an empty list for empty code", () => {
    expect(extractKwargsKeysFromPython("")).toEqual([]);
  });

  it("does not resolve dynamic (non-literal) kwargs access", () => {
    // A variable key can't be statically resolved — documented limitation,
    // not a false positive to guard against.
    const code = `
def score(self, output, **kwargs):
    key = "label"
    return kwargs.get(key)
`;
    expect(extractKwargsKeysFromPython(code)).toEqual([]);
  });

  it("ignores kwargs access mentioned inside a # comment", () => {
    const code = `
def score(self, output, **kwargs):
    # historically this read kwargs.get("legacy_col")
    return kwargs["real_col"]
`;
    expect(extractKwargsKeysFromPython(code)).toEqual(["real_col"]);
  });

  it("ignores kwargs access mentioned inside a docstring", () => {
    const code = `
def score(self, output, **kwargs):
    """Example: kwargs.get("doc_col") or kwargs['other_doc'].

    kwargs["also_in_doc"] should not count either.
    """
    return kwargs["real_col"]
`;
    expect(extractKwargsKeysFromPython(code)).toEqual(["real_col"]);
  });

  it("ignores a kwargs literal appearing inside a normal string", () => {
    const code = `
def score(self, output, **kwargs):
    msg = "kwargs.get('in_string')"
    return kwargs["actual"]
`;
    expect(extractKwargsKeysFromPython(code)).toEqual(["actual"]);
  });
});

describe("extractRequiredScoreParams", () => {
  it("returns strict score() positional params (excluding self/output)", () => {
    const code = `
def score(self, output, reference):
    return output == reference
`;
    expect(extractRequiredScoreParams(code)).toEqual(["reference"]);
  });

  it("keeps required positional params even with a trailing **kwargs", () => {
    // **kwargs only absorbs undeclared extras; `reference` is still required.
    const code = `
def score(self, output, reference, **kwargs):
    return output == reference
`;
    expect(extractRequiredScoreParams(code)).toEqual(["reference"]);
  });

  it("returns [] (defers to backend) when multiple score() defs are ambiguous", () => {
    const code = `
class ZMetric(BaseMetric):
    def score(self, output, reference):
        return output == reference

class AMetric(BaseMetric):
    def score(self, output, **kwargs):
        return 1.0
`;
    expect(extractRequiredScoreParams(code)).toEqual([]);
  });

  it("excludes params that have a default value", () => {
    const code = `
def score(self, output, reference, threshold=0.5):
    return output == reference
`;
    expect(extractRequiredScoreParams(code)).toEqual(["reference"]);
  });

  it("handles type-annotated params", () => {
    const code = `
def score(self, output: str, reference: str, category: str) -> ScoreResult:
    return output == reference
`;
    expect(extractRequiredScoreParams(code).sort()).toEqual([
      "category",
      "reference",
    ]);
  });

  it("returns [] when there is no score() method", () => {
    expect(extractRequiredScoreParams("def other(self): pass")).toEqual([]);
  });

  it("returns [] for empty code", () => {
    expect(extractRequiredScoreParams("")).toEqual([]);
  });
});

// The unknown-column check that gates submission
// (`useOptimizationsNewFormHandlers.missingDatasetVariables`) is exactly this
// diff: dataset columns referenced by the code metric (via `extractKwargsKeysFromPython`
// plus any explicit `arguments` map values) that are absent from the item
// source's actual columns.
describe("extractKwargsKeysFromPython — unknown-column detection", () => {
  it("flags a referenced key that is not among the dataset's columns", () => {
    const code = `kwargs["nonexistent_column"]`;
    const datasetVariables = ["text", "label"];
    const referenced = extractKwargsKeysFromPython(code);
    const missing = referenced.filter((key) => !datasetVariables.includes(key));
    expect(missing).toEqual(["nonexistent_column"]);
  });

  it("does not flag a referenced key that matches a dataset column", () => {
    const code = `kwargs.get("label")`;
    const datasetVariables = ["text", "label"];
    const referenced = extractKwargsKeysFromPython(code);
    const missing = referenced.filter((key) => !datasetVariables.includes(key));
    expect(missing).toEqual([]);
  });
});

describe("getOptimizationDefaultConfigByProvider — Anthropic", () => {
  it("seeds temperature for models that accept sampling params", () => {
    const config = getOptimizationDefaultConfigByProvider(
      PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_6,
    ) as LLMAnthropicConfigsType;

    expect(config.temperature).toBe(0);
  });

  it("omits temperature for Claude Opus 4.7", () => {
    const config = getOptimizationDefaultConfigByProvider(
      PROVIDER_TYPE.ANTHROPIC as COMPOSED_PROVIDER_TYPE,
      PROVIDER_MODEL_TYPE.CLAUDE_OPUS_4_7,
    ) as LLMAnthropicConfigsType;

    expect(config.temperature).toBeUndefined();
  });
});
