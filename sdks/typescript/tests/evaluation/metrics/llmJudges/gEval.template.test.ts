import { describe, it, expect } from "vitest";
import {
  generateCoTPrompt,
  generateQueryPrompt,
} from "@/evaluation/metrics/llmJudges/gEval/template";

describe("GEval Template Generation", () => {
  describe("generateCoTPrompt", () => {
    const taskIntroduction = "You evaluate code quality.";
    const evaluationCriteria = "Score from 0 (poor) to 10 (excellent).";

    it("should include task introduction", () => {
      const prompt = generateCoTPrompt(taskIntroduction, evaluationCriteria);

      expect(prompt).toContain(taskIntroduction);
    });

    it("should include evaluation criteria", () => {
      const prompt = generateCoTPrompt(taskIntroduction, evaluationCriteria);

      expect(prompt).toContain(evaluationCriteria);
    });

    it("should include Chain of Thought instruction", () => {
      const prompt = generateCoTPrompt(taskIntroduction, evaluationCriteria);

      expect(prompt).toContain("Chain of Thought");
      expect(prompt).toContain("Evaluation Steps");
    });

    it("should include score scale instruction", () => {
      const prompt = generateCoTPrompt(taskIntroduction, evaluationCriteria);

      expect(prompt).toContain("0 TO 10 RANGE");
      expect(prompt).toContain("SCORE VALUE MUST BE AN INTEGER");
    });

    it("should include TASK INTRODUCTION section", () => {
      const prompt = generateCoTPrompt(taskIntroduction, evaluationCriteria);

      expect(prompt).toContain("TASK INTRODUCTION:");
    });

    it("should include EVALUATION CRITERIA section", () => {
      const prompt = generateCoTPrompt(taskIntroduction, evaluationCriteria);

      expect(prompt).toContain("EVALUATION CRITERIA:");
    });

    it("should handle long task introduction", () => {
      const longIntro = "A".repeat(2000);
      const prompt = generateCoTPrompt(longIntro, evaluationCriteria);

      expect(prompt).toContain(longIntro);
    });

    it("should handle special characters", () => {
      const specialIntro = 'Task with "quotes" and\nnewlines';
      const prompt = generateCoTPrompt(specialIntro, evaluationCriteria);

      expect(prompt).toContain(specialIntro);
    });
  });

  describe("generateQueryPrompt", () => {
    const taskIntroduction = "You evaluate response quality.";
    const evaluationCriteria = "Rate clarity from 0 to 10.";
    const chainOfThought = "Step 1: Check grammar. Step 2: Check logic.";
    const output = "The answer is 42.";

    it("should include task introduction", () => {
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        output
      );

      expect(prompt).toContain(taskIntroduction);
    });

    it("should include evaluation criteria", () => {
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        output
      );

      expect(prompt).toContain(evaluationCriteria);
    });

    it("should include chain of thought", () => {
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        output
      );

      expect(prompt).toContain(chainOfThought);
    });

    it("should include the output to evaluate", () => {
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        output
      );

      expect(prompt).toContain(output);
    });

    it("should include JSON output format instruction", () => {
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        output
      );

      expect(prompt).toContain("JSON format");
      expect(prompt).toContain('"score"');
      expect(prompt).toContain('"reason"');
    });

    it("should include all four components in order", () => {
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        output
      );

      const taskIntroIndex = prompt.indexOf(taskIntroduction);
      const criteriaIndex = prompt.indexOf(evaluationCriteria);
      const cotIndex = prompt.indexOf(chainOfThought);
      const outputIndex = prompt.indexOf(output);

      expect(taskIntroIndex).toBeLessThan(criteriaIndex);
      expect(criteriaIndex).toBeLessThan(cotIndex);
      expect(cotIndex).toBeLessThan(outputIndex);
    });

    it("should handle empty output", () => {
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        ""
      );

      expect(prompt).toContain("*** INPUT:");
    });

    it("should handle very long output", () => {
      const longOutput = "B".repeat(5000);
      const prompt = generateQueryPrompt(
        taskIntroduction,
        evaluationCriteria,
        chainOfThought,
        longOutput
      );

      expect(prompt).toContain(longOutput);
    });
  });
});
