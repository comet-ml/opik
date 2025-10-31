import { describe, it, expect } from "vitest";
import {
  generateQueryWithContext,
  generateQueryNoContext,
  FEW_SHOT_EXAMPLES_WITH_CONTEXT,
  FEW_SHOT_EXAMPLES_NO_CONTEXT,
} from "@/evaluation/metrics/llmJudges/answerRelevance/template";

describe("AnswerRelevance Template Generation", () => {
  describe("generateQueryWithContext", () => {
    const input = "What is the capital of France?";
    const output = "The capital of France is Paris.";
    const context = [
      "France is a country in Europe.",
      "Paris is a major city.",
    ];

    it("should include input in generated query", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain(input);
    });

    it("should include output in generated query", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain(output);
    });

    it("should include context in generated query", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("Context:");
      expect(query).toContain(JSON.stringify(context));
    });

    it("should include few-shot examples", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("FEW-SHOT EXAMPLES");
      expect(query).toContain("Example 1:");
      expect(query).toContain("Low Relevance Score");
    });

    it("should include instructions", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("INSTRUCTIONS");
      expect(query).toContain("RELEVANCE SCORE");
    });

    it("should include chain of thoughts", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("CHAIN OF THOUGHTS");
    });

    it("should include what not to do section", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("WHAT NOT TO DO");
    });

    it("should include example output format", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("EXAMPLE OUTPUT FORMAT");
      expect(query).toContain("answer_relevance_score");
    });

    it("should handle empty context array", () => {
      const query = generateQueryWithContext(
        input,
        output,
        [],
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("Context:");
      expect(query).toContain("[]");
    });

    it("should handle single context item", () => {
      const singleContext = ["Single context item"];
      const query = generateQueryWithContext(
        input,
        output,
        singleContext,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("Single context item");
    });

    it("should handle many context items", () => {
      const manyContext = Array.from(
        { length: 10 },
        (_, i) => `Context item ${i + 1}`
      );
      const query = generateQueryWithContext(
        input,
        output,
        manyContext,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("Context item 1");
      expect(query).toContain("Context item 10");
    });

    it("should handle context with special characters", () => {
      const specialContext = [
        'Context with "quotes"',
        "Context with\nnewlines",
        "Context with \ttabs",
      ];
      const query = generateQueryWithContext(
        input,
        output,
        specialContext,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain("Context:");
      // Should be JSON stringified
      expect(query).toContain(JSON.stringify(specialContext));
    });

    it("should handle empty few-shot examples", () => {
      const query = generateQueryWithContext(input, output, context, []);

      expect(query).toContain("FEW-SHOT EXAMPLES");
      expect(query).toContain(input);
      expect(query).toContain(output);
    });

    it("should handle custom few-shot examples", () => {
      const customExamples = [
        {
          title: "Custom Example",
          input: "Custom input",
          output: "Custom output",
          context: ["Custom context"],
          answer_relevance_score: 0.5,
          reason: "Custom reason",
        },
      ];

      const query = generateQueryWithContext(
        input,
        output,
        context,
        customExamples
      );

      expect(query).toContain("Custom Example");
      expect(query).toContain("Custom input");
      expect(query).toContain("Custom output");
      expect(query).toContain("Custom reason");
    });

    it("should format few-shot examples correctly", () => {
      const query = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toMatch(/Example \d+:/);
      expect(query).toContain("**Input:**");
      expect(query).toContain("**Output:**");
      expect(query).toContain("**Context:**");
      expect(query).toContain("**Result:**");
    });

    it("should handle very long input text", () => {
      const longInput = "A".repeat(1000);
      const query = generateQueryWithContext(
        longInput,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain(longInput);
    });

    it("should handle very long output text", () => {
      const longOutput = "B".repeat(1000);
      const query = generateQueryWithContext(
        input,
        longOutput,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query).toContain(longOutput);
    });

    it("should handle very long context items", () => {
      const longContext = ["C".repeat(500), "D".repeat(500), "E".repeat(500)];
      const query = generateQueryWithContext(
        input,
        output,
        longContext,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(query.length).toBeGreaterThan(1500);
    });
  });

  describe("generateQueryNoContext", () => {
    const input = "What is the capital of France?";
    const output = "The capital of France is Paris.";

    it("should include input in generated query", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain(input);
    });

    it("should include output in generated query", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain(output);
    });

    it("should NOT include context section", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      // Should not have Context: label in inputs section
      const inputsSection = query.split("###INPUTS:###")[1];
      expect(inputsSection).not.toContain("Context:");
    });

    it("should include few-shot examples", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain("FEW-SHOT EXAMPLES");
      expect(query).toContain("Example 1:");
      expect(query).toContain("Low Relevance Score");
    });

    it("should include instructions", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain("INSTRUCTIONS");
      expect(query).toContain("RELEVANCE SCORE");
    });

    it("should include chain of thoughts", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain("CHAIN OF THOUGHTS");
    });

    it("should include what not to do section", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain("WHAT NOT TO DO");
    });

    it("should include example output format", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain("EXAMPLE OUTPUT FORMAT");
      expect(query).toContain("answer_relevance_score");
    });

    it("should handle empty few-shot examples", () => {
      const query = generateQueryNoContext(input, output, []);

      expect(query).toContain("FEW-SHOT EXAMPLES");
      expect(query).toContain(input);
      expect(query).toContain(output);
    });

    it("should handle custom few-shot examples", () => {
      const customExamples = [
        {
          title: "Custom No Context Example",
          input: "Custom input",
          output: "Custom output",
          answer_relevance_score: 0.7,
          reason: "Custom no-context reason",
        },
      ];

      const query = generateQueryNoContext(input, output, customExamples);

      expect(query).toContain("Custom No Context Example");
      expect(query).toContain("Custom input");
      expect(query).toContain("Custom no-context reason");
    });

    it("should format few-shot examples correctly without context", () => {
      const query = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toMatch(/Example \d+:/);
      expect(query).toContain("**Input:**");
      expect(query).toContain("**Output:**");
      expect(query).toContain("**Result:**");
      // Few-shot examples section should not have Context
      const examplesSection = query
        .split("###FEW-SHOT EXAMPLES###")[1]
        .split("###INPUTS:###")[0];
      expect(examplesSection).not.toContain("**Context:**");
    });

    it("should handle empty input", () => {
      const query = generateQueryNoContext(
        "",
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain("Input:");
      expect(query).toContain("Output:");
    });

    it("should handle empty output", () => {
      const query = generateQueryNoContext(
        input,
        "",
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain("Input:");
      expect(query).toContain("Output:");
    });

    it("should handle special characters in input", () => {
      const specialInput = 'Input with "quotes" and\nnewlines';
      const query = generateQueryNoContext(
        specialInput,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain(specialInput);
    });

    it("should handle special characters in output", () => {
      const specialOutput = 'Output with "quotes" and\nnewlines';
      const query = generateQueryNoContext(
        input,
        specialOutput,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(query).toContain(specialOutput);
    });
  });

  describe("Default few-shot examples", () => {
    it("should have three WITH_CONTEXT examples", () => {
      expect(FEW_SHOT_EXAMPLES_WITH_CONTEXT).toHaveLength(3);
    });

    it("should have three NO_CONTEXT examples", () => {
      expect(FEW_SHOT_EXAMPLES_NO_CONTEXT).toHaveLength(3);
    });

    it("should have valid scores in WITH_CONTEXT examples", () => {
      FEW_SHOT_EXAMPLES_WITH_CONTEXT.forEach((example) => {
        expect(example.answer_relevance_score).toBeGreaterThanOrEqual(0);
        expect(example.answer_relevance_score).toBeLessThanOrEqual(1);
      });
    });

    it("should have valid scores in NO_CONTEXT examples", () => {
      FEW_SHOT_EXAMPLES_NO_CONTEXT.forEach((example) => {
        expect(example.answer_relevance_score).toBeGreaterThanOrEqual(0);
        expect(example.answer_relevance_score).toBeLessThanOrEqual(1);
      });
    });

    it("should have required fields in WITH_CONTEXT examples", () => {
      FEW_SHOT_EXAMPLES_WITH_CONTEXT.forEach((example) => {
        expect(example).toHaveProperty("title");
        expect(example).toHaveProperty("input");
        expect(example).toHaveProperty("output");
        expect(example).toHaveProperty("context");
        expect(example).toHaveProperty("answer_relevance_score");
        expect(example).toHaveProperty("reason");
      });
    });

    it("should have required fields in NO_CONTEXT examples", () => {
      FEW_SHOT_EXAMPLES_NO_CONTEXT.forEach((example) => {
        expect(example).toHaveProperty("title");
        expect(example).toHaveProperty("input");
        expect(example).toHaveProperty("output");
        expect(example).toHaveProperty("answer_relevance_score");
        expect(example).toHaveProperty("reason");
        expect(example).not.toHaveProperty("context");
      });
    });

    it("should cover different score ranges in WITH_CONTEXT examples", () => {
      const scores = FEW_SHOT_EXAMPLES_WITH_CONTEXT.map(
        (ex) => ex.answer_relevance_score
      );
      expect(Math.min(...scores)).toBeLessThan(0.5); // Has low score
      expect(Math.max(...scores)).toBeGreaterThan(0.7); // Has high score
    });

    it("should cover different score ranges in NO_CONTEXT examples", () => {
      const scores = FEW_SHOT_EXAMPLES_NO_CONTEXT.map(
        (ex) => ex.answer_relevance_score
      );
      expect(Math.min(...scores)).toBeLessThan(0.5); // Has low score
      expect(Math.max(...scores)).toBeGreaterThan(0.7); // Has high score
    });
  });

  describe("Template differences", () => {
    const input = "Test input";
    const output = "Test output";
    const context = ["Test context"];

    it("should have different content for with/without context templates", () => {
      const queryWithContext = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );
      const queryNoContext = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(queryWithContext).not.toBe(queryNoContext);
      expect(queryWithContext.length).toBeGreaterThan(queryNoContext.length);
    });

    it("should mention context analysis in with-context version", () => {
      const queryWithContext = generateQueryWithContext(
        input,
        output,
        context,
        FEW_SHOT_EXAMPLES_WITH_CONTEXT
      );

      expect(queryWithContext.toLowerCase()).toContain("context");
      expect(queryWithContext).toContain("Understanding the Context and Input");
    });

    it("should not mention context analysis in no-context version", () => {
      const queryNoContext = generateQueryNoContext(
        input,
        output,
        FEW_SHOT_EXAMPLES_NO_CONTEXT
      );

      expect(queryNoContext).toContain("Understanding the Input");
      expect(queryNoContext).not.toContain("Understanding the Context");
    });
  });
});
