import { describe, expect, it } from "vitest";
import uniq from "lodash/uniq";

import {
  LLM_PROMPT_TEMPLATES,
  LLM_PROMPT_THREAD_TEMPLATES,
} from "@/constants/llm";
import { getTextFromMessageContent } from "@/lib/llm";
import { LLMPromptTemplate } from "@/types/llm";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";

const allTemplates: LLMPromptTemplate[] =
  Object.values(LLM_PROMPT_TEMPLATES).flat();

const promptText = (template: LLMPromptTemplate) =>
  template.messages
    .map((message) => getTextFromMessageContent(message.content))
    .join("\n");

const mustacheTags = (text: string) =>
  Array.from(text.matchAll(/{{\s*([^}]+?)\s*}}/g), (match) => match[1]);

describe("LLM-as-judge prompt templates", () => {
  describe("thread scope", () => {
    it.each(LLM_PROMPT_THREAD_TEMPLATES.map((t) => [t.label, t] as const))(
      "%s uses {{context}} exactly once and no other variable",
      (_label, template) => {
        const tags = mustacheTags(promptText(template));

        expect(tags).toEqual(["context"]);
        expect(Object.keys(template.variables ?? {})).toEqual(["context"]);
      },
    );
  });

  describe("trace and span scope", () => {
    it.each(
      [
        ...LLM_PROMPT_TEMPLATES[EVALUATORS_RULE_SCOPE.trace],
        ...LLM_PROMPT_TEMPLATES[EVALUATORS_RULE_SCOPE.span],
      ].map((t) => [t.label, t] as const),
    )(
      "%s only uses variables that resolve without a mapping",
      (_label, template) => {
        // There is no mapping UI any more: every variable must be a field path
        // (input/output/metadata…) or a reserved sentinel, mapped to itself.
        Object.entries(template.variables ?? {}).forEach(([name, value]) => {
          expect(value).toBe(name);
          expect(name).toMatch(
            /^(input|output|metadata)(\.|\[|$)|^(spans|trace|span)$/,
          );
        });
      },
    );
  });

  describe("every scope", () => {
    it.each(allTemplates.map((t) => [t.label, t] as const))(
      "%s leaves the output format to the backend",
      (_label, template) => {
        // The backend derives the response shape from the score definition
        // (JSON schema or an appended instruction). A fenced "return exactly
        // this JSON" block in the prompt duplicates that and goes stale as
        // soon as a score is renamed or retyped.
        expect(promptText(template)).not.toContain("```json");
        expect(promptText(template)).not.toMatch(/Final Output Format/i);
      },
    );

    it.each(allTemplates.map((t) => [t.label, t] as const))(
      "%s declares unique, named scores",
      (_label, template) => {
        const names = template.schema.map((score) => score.name);

        expect(names.every((name) => name.trim().length > 0)).toBe(true);
        expect(uniq(names)).toHaveLength(names.length);
      },
    );

    it("labels templates as readable names, not identifiers", () => {
      // Regression guard for the former "AnswerRelevance" label.
      const camelCased = allTemplates
        .map((template) => template.label)
        .filter((label) => /[a-z][A-Z]/.test(label));

      expect(camelCased).toEqual([]);
    });
  });
});
