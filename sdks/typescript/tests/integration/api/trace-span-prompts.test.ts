import { describe, it, expect, beforeAll, afterEach, afterAll } from "vitest";
import { Opik } from "@/index";
import type { PromptInfoDict } from "@/tracer/types";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

// Test constants
const TEST_TIMEOUT = 40000;
const WAIT_FOR_TIMEOUT_SECONDS = 30;

// Helper type for metadata
interface TraceMetadata {
  [key: string]: unknown;
  opik_prompts?: PromptInfoDict[];
}

describe.skipIf(!shouldRunApiTests)("Trace Prompts Integration Tests", () => {
  let client: Opik;
  const testProjectName = `test-prompts-${Date.now()}`;
  const createdPromptIds: string[] = [];

  beforeAll(() => {
    if (shouldRunApiTests) {
      console.log(getIntegrationTestStatus());
      client = new Opik({ projectName: testProjectName });
    }
  });

  afterEach(async () => {
    // Clean up created prompts
    if (createdPromptIds.length > 0) {
      try {
        await client.deletePrompts(createdPromptIds);
      } catch (error) {
        console.warn("Failed to delete prompts during cleanup:", error);
      }
      createdPromptIds.length = 0;
    }
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  /**
   * Helper to search for traces and wait for them to be indexed
   */
  const searchWithWait = async (
    filterString: string,
    expectedCount: number,
    timeout: number = WAIT_FOR_TIMEOUT_SECONDS
  ) => {
    return await client.searchTraces({
      projectName: testProjectName,
      filterString,
      waitForAtLeast: expectedCount,
      waitForTimeout: timeout,
    });
  };

  /**
   * Helper to verify prompt structure matches PromptInfoDict format
   */
  const verifyPromptInfoDict = (
    promptInfo: PromptInfoDict,
    expectedName: string,
    expectedTemplate: string
  ) => {
    expect(promptInfo.name).toBe(expectedName);
    expect(promptInfo.id).toBeDefined();
    expect(typeof promptInfo.id).toBe("string");
    expect(promptInfo.version).toBeDefined();
    expect(promptInfo.version.template).toBe(expectedTemplate);
    expect(promptInfo.version.id).toBeDefined();
    expect(typeof promptInfo.version.id).toBe("string");
    expect(promptInfo.version.commit).toBeDefined();
    expect(typeof promptInfo.version.commit).toBe("string");
  };

  it(
    "should attach single prompt to trace and serialize to metadata.opik_prompts",
    async () => {
      const timestamp = Date.now();
      const promptName = `test-prompt-trace-${timestamp}`;
      const promptTemplate = "Answer the question: {{question}}";
      const traceName = `trace-with-prompt-${timestamp}`;

      // Create a prompt
      const prompt = await client.createPrompt({
        name: promptName,
        prompt: promptTemplate,
        metadata: { testCase: "single-trace-prompt" },
      });
      createdPromptIds.push(prompt.id);

      // Create a trace
      const trace = client.trace({
        name: traceName,
        input: { query: "test input" },
      });

      // Update trace with prompt
      trace.update({ prompts: [prompt] });
      trace.end();

      await client.flush();

      // Search for the trace
      const results = await searchWithWait(`name = "${traceName}"`, 1);

      expect(results.length).toBeGreaterThanOrEqual(1);
      const foundTrace = results.find((t) => t.name === traceName);
      expect(foundTrace).toBeDefined();

      // Verify metadata.opik_prompts exists and is an array
      const metadata = foundTrace?.metadata as TraceMetadata;
      expect(metadata.opik_prompts).toBeDefined();
      expect(Array.isArray(metadata.opik_prompts)).toBe(true);
      expect(metadata.opik_prompts).toHaveLength(1);

      // Verify prompt structure
      const serializedPrompt = metadata.opik_prompts![0];
      verifyPromptInfoDict(serializedPrompt, promptName, promptTemplate);
    },
    TEST_TIMEOUT
  );

  it(
    "should attach multiple prompts to trace and preserve order",
    async () => {
      const timestamp = Date.now();
      const traceName = `trace-multiple-prompts-${timestamp}`;

      // Create 3 different prompts
      const prompt1 = await client.createPrompt({
        name: `prompt-1-${timestamp}`,
        prompt: "First prompt: {{var1}}",
      });
      createdPromptIds.push(prompt1.id);

      const prompt2 = await client.createPrompt({
        name: `prompt-2-${timestamp}`,
        prompt: "Second prompt: {{var2}}",
      });
      createdPromptIds.push(prompt2.id);

      const prompt3 = await client.createPrompt({
        name: `prompt-3-${timestamp}`,
        prompt: "Third prompt: {{var3}}",
      });
      createdPromptIds.push(prompt3.id);

      // Create a trace
      const trace = client.trace({
        name: traceName,
        input: { query: "test input" },
      });

      // Update trace with multiple prompts
      trace.update({ prompts: [prompt1, prompt2, prompt3] });
      trace.end();

      await client.flush();

      // Search for the trace
      const results = await searchWithWait(`name = "${traceName}"`, 1);

      expect(results.length).toBeGreaterThanOrEqual(1);
      const foundTrace = results.find((t) => t.name === traceName);
      expect(foundTrace).toBeDefined();

      // Verify metadata.opik_prompts has 3 prompts
      const metadata = foundTrace?.metadata as TraceMetadata;
      expect(metadata.opik_prompts).toBeDefined();
      expect(Array.isArray(metadata.opik_prompts)).toBe(true);
      expect(metadata.opik_prompts).toHaveLength(3);

      // Verify each prompt structure and order
      verifyPromptInfoDict(
        metadata.opik_prompts![0],
        `prompt-1-${timestamp}`,
        "First prompt: {{var1}}"
      );
      verifyPromptInfoDict(
        metadata.opik_prompts![1],
        `prompt-2-${timestamp}`,
        "Second prompt: {{var2}}"
      );
      verifyPromptInfoDict(
        metadata.opik_prompts![2],
        `prompt-3-${timestamp}`,
        "Third prompt: {{var3}}"
      );
    },
    TEST_TIMEOUT
  );

  it(
    "should preserve existing metadata when adding prompts to trace",
    async () => {
      const timestamp = Date.now();
      const traceName = `trace-metadata-preservation-${timestamp}`;
      const promptName = `prompt-metadata-test-${timestamp}`;

      // Create a prompt
      const prompt = await client.createPrompt({
        name: promptName,
        prompt: "Test: {{test}}",
      });
      createdPromptIds.push(prompt.id);

      // Create a trace with existing metadata
      const trace = client.trace({
        name: traceName,
        input: { query: "test" },
        metadata: { customKey: "customValue", originalField: 123 },
      });

      // Update trace with prompts and additional metadata
      trace.update({
        prompts: [prompt],
        metadata: { newField: "newValue" },
      });
      trace.end();

      await client.flush();

      // Search for the trace
      const results = await searchWithWait(`name = "${traceName}"`, 1);
      const foundTrace = results.find((t) => t.name === traceName);
      expect(foundTrace).toBeDefined();

      // Verify all metadata fields are present
      const metadata = foundTrace?.metadata as TraceMetadata;

      // Original metadata
      expect(metadata.customKey).toBe("customValue");
      expect(metadata.originalField).toBe(123);

      // New metadata
      expect(metadata.newField).toBe("newValue");

      // Prompts metadata
      expect(metadata.opik_prompts).toBeDefined();
      expect(metadata.opik_prompts).toHaveLength(1);
      verifyPromptInfoDict(
        metadata.opik_prompts![0],
        promptName,
        "Test: {{test}}"
      );
    },
    TEST_TIMEOUT
  );
});
