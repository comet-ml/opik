import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { TestSuite } from "@/evaluation/suite/TestSuite";
import { runTests } from "@/evaluation/suite/runTests";

vi.mock("@/evaluation/suite_evaluators/LLMJudge", () => {
  class MockLLMJudge {
    assertions: string[];
    name: string;
    modelName: string;
    constructor(opts: { assertions: string[]; name?: string }) {
      this.assertions = opts.assertions;
      this.name = opts.name ?? "llm_judge";
      this.modelName = "gpt-5-nano";
    }
    toConfig() {
      return {
        name: this.name,
        schema: this.assertions.map((a: string) => ({
          name: a,
          type: "BOOLEAN",
        })),
        model: { name: this.modelName },
      };
    }
    static fromConfig = vi.fn();
  }
  return { LLMJudge: MockLLMJudge };
});

vi.mock("@/evaluation/suite/evaluateTestSuite", () => ({
  evaluateTestSuite: vi.fn().mockResolvedValue({
    experimentId: "exp-1",
    experimentName: "test-exp",
    testResults: [],
    errors: [],
  }),
}));

describe("runTests", () => {
  let opikClient: OpikClient;
  let testDataset: Dataset;
  let suite: TestSuite;

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    testDataset = new Dataset(
      { id: "suite-ds-id", name: "test-suite", description: "Test suite" },
      opikClient
    );

    suite = new TestSuite(testDataset, opikClient);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("should delegate to evaluateTestSuite and return TestSuiteResult", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );
    vi.mocked(evaluateTestSuite).mockResolvedValue({
      experimentId: "exp-1",
      experimentName: "test-exp",
      testResults: [
        {
          testCase: {
            traceId: "trace-1",
            datasetItemId: "item-1",
            scoringInputs: {},
            taskOutput: { output: "hello" },
          },
          scoreResults: [{ name: "quality", value: 1 }],
          resolvedExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        },
      ],
      errors: [],
    });

    const mockTask = vi.fn().mockResolvedValue({ input: "hi", output: "hello" });

    const result = await runTests({
      testSuite: suite,
      task: mockTask,
      experimentName: "test-experiment",
    });

    expect(result).toEqual(
      expect.objectContaining({
        experimentId: "exp-1",
        experimentName: "test-exp",
        allItemsPassed: true,
        itemsPassed: 1,
        itemsTotal: 1,
        passRate: 1.0,
      })
    );
    expect(result.itemResults).toBeInstanceOf(Map);
    expect(result.itemResults.has("item-1")).toBe(true);
  });

  it("should pass model option to evaluateTestSuite as evaluatorModel", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );
    vi.mocked(evaluateTestSuite).mockResolvedValue({
      experimentId: "exp-2",
      experimentName: "test-exp-2",
      testResults: [],
      errors: [],
    });

    const mockTask = vi.fn().mockResolvedValue({ input: "hi", output: "hello" });

    await runTests({
      testSuite: suite,
      task: mockTask,
      experimentName: "test-experiment",
      model: "claude-sonnet-4",
    });

    expect(evaluateTestSuite).toHaveBeenCalledWith(
      expect.objectContaining({ evaluatorModel: "claude-sonnet-4" })
    );
  });

  it("should not call getVersionInfo separately (evaluateTestSuite handles everything)", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );
    vi.mocked(evaluateTestSuite).mockResolvedValue({
      experimentId: "exp-2",
      experimentName: "test-exp-2",
      testResults: [
        {
          testCase: {
            traceId: "trace-2",
            datasetItemId: "item-2",
            scoringInputs: {},
            taskOutput: { output: "world" },
          },
          scoreResults: [{ name: "quality", value: 1 }],
          resolvedExecutionPolicy: { runsPerItem: 1, passThreshold: 1 },
        },
      ],
      errors: [],
    });

    const getVersionInfoSpy = vi
      .spyOn(testDataset, "getVersionInfo")
      .mockResolvedValue({
        id: "v1",
        executionPolicy: { runsPerItem: 1, passThreshold: 1 },
      });

    const mockTask = vi.fn().mockResolvedValue({ input: "hi", output: "hello" });

    await runTests({
      testSuite: suite,
      task: mockTask,
      experimentName: "test-experiment",
    });

    expect(getVersionInfoSpy).not.toHaveBeenCalled();
  });

  it("should pass testSuite dataset and client to evaluateTestSuite", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );
    vi.mocked(evaluateTestSuite).mockResolvedValue({
      experimentId: "exp-3",
      experimentName: "test-exp-3",
      testResults: [],
      errors: [],
    });

    const mockTask = vi.fn().mockResolvedValue({ input: "hi", output: "hello" });

    await runTests({
      testSuite: suite,
      task: mockTask,
      experimentName: "my-exp",
      projectName: "my-project",
      experimentConfig: { key: "value" },
      experimentTags: ["tag1"],
    });

    expect(evaluateTestSuite).toHaveBeenCalledWith(
      expect.objectContaining({
        dataset: testDataset,
        client: opikClient,
        experimentName: "my-exp",
        projectName: "my-project",
        experimentConfig: { key: "value" },
        tags: ["tag1"],
      })
    );
  });

  it("should wrap task with validateTaskResult", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );

    // Capture the task passed to evaluateTestSuite
    let capturedTask: ((item: Record<string, unknown>) => Promise<Record<string, unknown>>) | undefined;
    vi.mocked(evaluateTestSuite).mockImplementation(async (opts) => {
      capturedTask = opts.task as (item: Record<string, unknown>) => Promise<Record<string, unknown>>;
      return {
        experimentId: "exp-4",
        testResults: [],
        errors: [],
      };
    });

    // Task that returns invalid result (missing 'input' and 'output')
    const badTask = vi.fn().mockResolvedValue({ foo: "bar" });

    await runTests({ testSuite: suite, task: badTask });

    expect(capturedTask).toBeDefined();
    await expect(capturedTask!({ input: "test" })).rejects.toThrow(
      /missing: input, output/
    );
  });

  it("should reject task result missing only output key", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );

    let capturedTask: ((item: Record<string, unknown>) => Promise<Record<string, unknown>>) | undefined;
    vi.mocked(evaluateTestSuite).mockImplementation(async (opts) => {
      capturedTask = opts.task as (item: Record<string, unknown>) => Promise<Record<string, unknown>>;
      return { experimentId: "exp-5", testResults: [], errors: [] };
    });

    const taskMissingOutput = vi.fn().mockResolvedValue({ input: "hi" });
    await runTests({ testSuite: suite, task: taskMissingOutput });

    expect(capturedTask).toBeDefined();
    await expect(capturedTask!({ input: "test" })).rejects.toThrow(
      /missing: output/
    );
  });

  it("should reject non-object task result", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );

    let capturedTask: ((item: Record<string, unknown>) => Promise<Record<string, unknown>>) | undefined;
    vi.mocked(evaluateTestSuite).mockImplementation(async (opts) => {
      capturedTask = opts.task as (item: Record<string, unknown>) => Promise<Record<string, unknown>>;
      return { experimentId: "exp-6", testResults: [], errors: [] };
    });

    const taskReturnsString = vi.fn().mockResolvedValue("not an object");
    await runTests({ testSuite: suite, task: taskReturnsString });

    expect(capturedTask).toBeDefined();
    await expect(capturedTask!({ input: "test" })).rejects.toThrow(
      /must return an object/
    );
  });

  it("should work with only required options (testSuite and task)", async () => {
    const { evaluateTestSuite } = await import(
      "@/evaluation/suite/evaluateTestSuite"
    );
    vi.mocked(evaluateTestSuite).mockResolvedValue({
      experimentId: "exp-7",
      experimentName: "minimal-exp",
      testResults: [],
      errors: [],
    });

    const mockTask = vi.fn().mockResolvedValue({ input: "hi", output: "hello" });

    const result = await runTests({ testSuite: suite, task: mockTask });

    expect(result).toEqual(
      expect.objectContaining({
        experimentId: "exp-7",
        allItemsPassed: true,
        itemsPassed: 0,
        itemsTotal: 0,
      })
    );
    expect(evaluateTestSuite).toHaveBeenCalledWith(
      expect.objectContaining({
        dataset: testDataset,
        client: opikClient,
      })
    );
  });
});
