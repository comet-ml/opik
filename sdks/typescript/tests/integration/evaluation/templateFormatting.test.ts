import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import { evaluatePrompt } from "@/evaluation/evaluatePrompt";
import { PromptType } from "@/prompt/types";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "../api/shouldRunIntegrationTests";
import { cleanupDatasets, createSimpleDataset } from "./helpers/testData";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)("Template Formatting Integration", () => {
  let client: Opik;
  const createdDatasetNames: string[] = [];

  beforeAll(() => {
    console.log(getIntegrationTestStatus());

    if (!shouldRunApiTests) {
      return;
    }

    client = new Opik();
  });

  afterEach(async () => {
    await cleanupDatasets(client, createdDatasetNames);
    createdDatasetNames.length = 0;
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  describe("Mustache Template (Default)", () => {
    it("should format Mustache templates with single variable", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        { name: "Alice" },
        { name: "Bob" },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "Hello {{name}}!" }],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
      expect(typeof result.testResults[0].testCase.taskOutput.output).toBe(
        "string"
      );
    }, 60000);

    it("should format Mustache templates with multiple variables", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        {
          user_name: "Alice",
          role: "admin",
          question: "What is AI?",
          context: "Machine learning discussion",
        },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content:
              "User: {{user_name}}\nRole: {{role}}\nQuestion: {{question}}\nContext: {{context}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should handle Mustache templates in multiple messages", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        { system_role: "helpful assistant", user_query: "What is TypeScript?" },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "system",
            content: "You are a {{system_role}}.",
          },
          {
            role: "user",
            content: "{{user_query}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);
  });

  describe("Jinja2 Template", () => {
    it("should format Jinja2 templates with single variable", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        { name: "Alice" },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [{ role: "user", content: "Hello {{ name }}!" }],
        templateType: PromptType.JINJA2,
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should format Jinja2 templates with multiple variables", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        {
          first_name: "Alice",
          last_name: "Smith",
          age: 30,
        },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content: "Name: {{ first_name }} {{ last_name }}\nAge: {{ age }}",
          },
        ],
        templateType: PromptType.JINJA2,
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);
  });

  describe("Complex Template Scenarios", () => {
    it("should handle templates with 4+ placeholders", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        {
          field1: "value1",
          field2: "value2",
          field3: "value3",
          field4: "value4",
          field5: "value5",
        },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content:
              "F1: {{field1}}, F2: {{field2}}, F3: {{field3}}, F4: {{field4}}, F5: {{field5}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should handle nested object access in templates", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        {
          user: "Alice",
          metadata: "version: 1.0",
        },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content: "User: {{user}}\nMetadata: {{metadata}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should handle templates with special characters", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        {
          text: "Hello! How are you? I'm fine.",
          code: 'const x = "test";',
        },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content: "Text: {{text}}\nCode: {{code}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should handle templates with numeric and boolean values", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        {
          count: 42,
          score: 95.5,
          is_active: true,
          is_admin: false,
        },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content:
              "Count: {{count}}, Score: {{score}}, Active: {{is_active}}, Admin: {{is_admin}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);
  });

  describe("Template Edge Cases", () => {
    it("should handle empty string values in templates", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        {
          name: "Alice",
          description: "",
        },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content: "Name: {{name}}\nDescription: {{description}}",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);

    it("should handle templates with no variables", async () => {
      const dataset = await createSimpleDataset(client, undefined, [
        { placeholder: "value" },
      ]);
      createdDatasetNames.push(dataset.name);

      const result = await evaluatePrompt({
        dataset,
        messages: [
          {
            role: "user",
            content: "This is a static template with no variables.",
          },
        ],
        nbSamples: 1,
      });

      expect(result.testResults[0].testCase.taskOutput.output).toBeDefined();
    }, 60000);
  });
});
