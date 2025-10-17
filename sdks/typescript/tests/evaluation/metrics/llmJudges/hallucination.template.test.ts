import { describe, it, expect } from "vitest";
import {
  generateQueryWithContext,
  generateQueryWithoutContext,
} from "@/evaluation/metrics/llmJudges/hallucination/template";

describe("Hallucination Template Generation", () => {
  describe("generateQueryWithContext", () => {
    const input = "Who wrote Hamlet?";
    const output = "Shakespeare wrote Hamlet in 1600.";
    const context = [
      "William Shakespeare was an English playwright.",
      "Hamlet was written around 1600.",
    ];

    it("should include input in generated query", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain(input);
    });

    it("should include output in generated query", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain(output);
    });

    it("should include context in generated query", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("CONTEXT:");
      expect(query).toContain(JSON.stringify(context));
    });

    it("should include guidelines", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("Guidelines:");
      expect(query).toContain("not introduce new information");
      expect(query).toContain("not contradict");
    });

    it("should include score range explanation", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("your score between 0.0 and 1.0");
      expect(query).toContain("0.0:");
      expect(query).toContain("1.0:");
      expect(query).toContain("faithful");
    });

    it("should include JSON output format requirement", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("JSON format");
      expect(query).toContain('"score"');
      expect(query).toContain('"reason"');
    });

    it("should specify reason as array format", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain('["reason 1", "reason 2"]');
    });

    it("should handle empty context array", () => {
      const query = generateQueryWithContext(input, output, []);

      expect(query).toContain("CONTEXT:");
      expect(query).toContain("[]");
    });

    it("should handle single context item", () => {
      const singleContext = ["Single context item"];
      const query = generateQueryWithContext(input, output, singleContext);

      expect(query).toContain(JSON.stringify(singleContext));
    });

    it("should handle many context items", () => {
      const manyContext = Array.from(
        { length: 10 },
        (_, i) => `Context ${i + 1}`
      );
      const query = generateQueryWithContext(input, output, manyContext);

      expect(query).toContain("Context 1");
      expect(query).toContain("Context 10");
    });

    it("should handle context with special characters", () => {
      const specialContext = [
        'Context with "quotes"',
        "Context with\nnewlines",
        "Context with \ttabs",
      ];
      const query = generateQueryWithContext(input, output, specialContext);

      expect(query).toContain(JSON.stringify(specialContext));
    });

    it("should handle empty few-shot examples", () => {
      const query = generateQueryWithContext(input, output, context, []);

      expect(query).toContain(input);
      expect(query).toContain(output);
      // Empty examples should not add EXAMPLES section
      expect(query).not.toContain("EXAMPLES:");
    });

    it("should include few-shot examples when provided", () => {
      const examples = [
        {
          input: "Example input",
          output: "Example output",
          context: ["Example context"],
          score: 0.8,
          reason: "Example reason",
        },
      ];

      const query = generateQueryWithContext(input, output, context, examples);

      expect(query).toContain("EXAMPLES:");
      expect(query).toContain("Example input");
      expect(query).toContain("Example output");
      expect(query).toContain("Example context");
      expect(query).toContain("Example reason");
    });

    it("should format few-shot examples with context", () => {
      const examples = [
        {
          input: "Test input",
          output: "Test output",
          context: ["Test context"],
          score: 0.5,
          reason: "Test reason",
        },
      ];

      const query = generateQueryWithContext(input, output, context, examples);

      expect(query).toContain("<example>");
      expect(query).toContain("</example>");
      expect(query).toContain("Context:");
    });

    it("should handle multiple few-shot examples", () => {
      const examples = [
        {
          input: "Input 1",
          output: "Output 1",
          context: ["Context 1"],
          score: 0.2,
          reason: "Reason 1",
        },
        {
          input: "Input 2",
          output: "Output 2",
          context: ["Context 2"],
          score: 0.8,
          reason: "Reason 2",
        },
      ];

      const query = generateQueryWithContext(input, output, context, examples);

      expect(query).toContain("Input 1");
      expect(query).toContain("Input 2");
      expect(query).toContain("Reason 1");
      expect(query).toContain("Reason 2");
    });

    it("should handle very long input text", () => {
      const longInput = "A".repeat(1000);
      const query = generateQueryWithContext(longInput, output, context);

      expect(query).toContain(longInput);
    });

    it("should handle very long output text", () => {
      const longOutput = "B".repeat(1000);
      const query = generateQueryWithContext(input, longOutput, context);

      expect(query).toContain(longOutput);
    });

    it("should handle very long context items", () => {
      const longContext = ["C".repeat(500), "D".repeat(500)];
      const query = generateQueryWithContext(input, output, longContext);

      expect(query.length).toBeGreaterThan(1000);
    });

    it("should mention partial hallucinations in guidelines", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("partial hallucinations");
    });

    it("should mention subject attention in guidelines", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("subject of statements");
      expect(query).toContain("correctly associated");
    });

    it("should mention misattributions in guidelines", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("misattributions");
      expect(query).toContain("conflations");
    });

    it("should clarify input is for context only", () => {
      const query = generateQueryWithContext(input, output, context);

      expect(query).toContain("INPUT (for context only");
      expect(query).toContain("not to be used for faithfulness");
    });
  });

  describe("generateQueryWithoutContext", () => {
    const input = "What is 2+2?";
    const output = "2+2 equals 4.";

    it("should include input in generated query", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain(input);
    });

    it("should include output in generated query", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain(output);
    });

    it("should NOT include context section", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).not.toContain("CONTEXT:");
    });

    it("should include guidelines for factual accuracy", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain("Guidelines:");
      expect(query).toContain("generally accepted facts");
      expect(query).toContain("well-established facts");
    });

    it("should include score range explanation", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain("your score between 0.0 and 1.0");
      expect(query).toContain("0.0:");
      expect(query).toContain("1.0:");
    });

    it("should include JSON output format requirement", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain("JSON format");
      expect(query).toContain('"score"');
      expect(query).toContain('"reason"');
    });

    it("should specify reason as array format", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain('["some reason 1", "some reason 2"]');
    });

    it("should handle empty few-shot examples", () => {
      const query = generateQueryWithoutContext(input, output, []);

      expect(query).toContain(input);
      expect(query).toContain(output);
      expect(query).not.toContain("EXAMPLES:");
    });

    it("should include few-shot examples when provided", () => {
      const examples = [
        {
          input: "Example input",
          output: "Example output",
          context: ["This should be ignored"],
          score: 0.6,
          reason: "Example reason",
        },
      ];

      const query = generateQueryWithoutContext(input, output, examples);

      expect(query).toContain("EXAMPLES:");
      expect(query).toContain("Example input");
      expect(query).toContain("Example output");
      // Context should NOT be included in no-context template
      expect(query).not.toContain("This should be ignored");
    });

    it("should format few-shot examples without context", () => {
      const examples = [
        {
          input: "Test input",
          output: "Test output",
          context: ["Should not appear"],
          score: 0.5,
          reason: "Test reason",
        },
      ];

      const query = generateQueryWithoutContext(input, output, examples);

      const examplesSection = query
        .split("EXAMPLES:")[1]
        ?.split("INPUT (for context only")[0];
      if (examplesSection) {
        expect(examplesSection).not.toContain("Context:");
        expect(examplesSection).not.toContain("Should not appear");
      }
    });

    it("should handle multiple few-shot examples", () => {
      const examples = [
        {
          input: "Input 1",
          output: "Output 1",
          context: [],
          score: 0.1,
          reason: "Reason 1",
        },
        {
          input: "Input 2",
          output: "Output 2",
          context: [],
          score: 0.9,
          reason: "Reason 2",
        },
      ];

      const query = generateQueryWithoutContext(input, output, examples);

      expect(query).toContain("Input 1");
      expect(query).toContain("Input 2");
      expect(query).toContain("Reason 1");
      expect(query).toContain("Reason 2");
    });

    it("should handle empty input", () => {
      const query = generateQueryWithoutContext("", output);

      expect(query).toContain("INPUT");
      expect(query).toContain("OUTPUT");
    });

    it("should handle empty output", () => {
      const query = generateQueryWithoutContext(input, "");

      expect(query).toContain("INPUT");
      expect(query).toContain("OUTPUT");
    });

    it("should handle special characters in input", () => {
      const specialInput = 'Input with "quotes" and\nnewlines';
      const query = generateQueryWithoutContext(specialInput, output);

      expect(query).toContain(specialInput);
    });

    it("should handle special characters in output", () => {
      const specialOutput = 'Output with "quotes" and\nnewlines';
      const query = generateQueryWithoutContext(input, specialOutput);

      expect(query).toContain(specialOutput);
    });

    it("should mention partial hallucinations in guidelines", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain("partial hallucinations");
    });

    it("should clarify input is for context only", () => {
      const query = generateQueryWithoutContext(input, output);

      expect(query).toContain("INPUT (for context only");
      expect(query).toContain("not to be used for faithfulness");
    });
  });

  describe("Template differences", () => {
    const input = "Test input";
    const output = "Test output";
    const context = ["Test context"];

    it("should have different content for with/without context templates", () => {
      const queryWithContext = generateQueryWithContext(input, output, context);
      const queryWithoutContext = generateQueryWithoutContext(input, output);

      expect(queryWithContext).not.toBe(queryWithoutContext);
      expect(queryWithContext.length).toBeGreaterThan(
        queryWithoutContext.length
      );
    });

    it("should mention context faithfulness in with-context version", () => {
      const queryWithContext = generateQueryWithContext(input, output, context);

      expect(queryWithContext).toContain("faithful to the CONTEXT");
      expect(queryWithContext).toContain(
        "beyond what's provided in the CONTEXT"
      );
    });

    it("should mention general knowledge in without-context version", () => {
      const queryWithoutContext = generateQueryWithoutContext(input, output);

      expect(queryWithoutContext).toContain("generally accepted facts");
      expect(queryWithoutContext).toContain("reliable information");
    });

    it("should have CONTEXT section only in with-context version", () => {
      const queryWithContext = generateQueryWithContext(input, output, context);
      const queryWithoutContext = generateQueryWithoutContext(input, output);

      expect(queryWithContext).toContain("CONTEXT:");
      expect(queryWithoutContext).not.toContain("CONTEXT:");
    });

    it("should both clarify INPUT usage", () => {
      const queryWithContext = generateQueryWithContext(input, output, context);
      const queryWithoutContext = generateQueryWithoutContext(input, output);

      expect(queryWithContext).toContain("INPUT (for context only");
      expect(queryWithoutContext).toContain("INPUT (for context only");
    });

    it("should both require JSON format", () => {
      const queryWithContext = generateQueryWithContext(input, output, context);
      const queryWithoutContext = generateQueryWithoutContext(input, output);

      expect(queryWithContext).toContain("JSON format");
      expect(queryWithoutContext).toContain("JSON format");
    });

    it("should both mention partial hallucinations", () => {
      const queryWithContext = generateQueryWithContext(input, output, context);
      const queryWithoutContext = generateQueryWithoutContext(input, output);

      expect(queryWithContext).toContain("partial hallucinations");
      expect(queryWithoutContext).toContain("partial hallucinations");
    });
  });

  describe("Few-shot example formatting", () => {
    it("should format examples with proper XML-like tags", () => {
      const examples = [
        {
          input: "Test",
          output: "Test output",
          context: ["Test context"],
          score: 0.5,
          reason: "Test reason",
        },
      ];

      const query = generateQueryWithContext(
        "Input",
        "Output",
        ["Context"],
        examples
      );

      expect(query).toContain("<example>");
      expect(query).toContain("</example>");
    });

    it("should include score in example format", () => {
      const examples = [
        {
          input: "Test",
          output: "Test output",
          context: ["Test context"],
          score: 0.75,
          reason: "Test reason",
        },
      ];

      const query = generateQueryWithContext(
        "Input",
        "Output",
        ["Context"],
        examples
      );

      expect(query).toContain('"score": "0.75"');
    });

    it("should include reason in example format", () => {
      const examples = [
        {
          input: "Test",
          output: "Test output",
          context: ["Test context"],
          score: 0.5,
          reason: "Specific test reason",
        },
      ];

      const query = generateQueryWithContext(
        "Input",
        "Output",
        ["Context"],
        examples
      );

      expect(query).toContain('"reason": "Specific test reason"');
    });
  });
});
