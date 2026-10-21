import { createElement, ReactNode } from "react";
import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useTestSuitePromptResults, {
  areAllRowItemsScored,
} from "./useTestSuitePromptResults";
import { getExperimentById } from "@/api/datasets/useExperimentById";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import usePlaygroundStore from "@/store/PlaygroundStore";
import {
  DATASET_TYPE,
  Experiment,
  EXPERIMENT_STATUS,
  ExperimentsCompare,
} from "@/types/datasets";
import { MetricType } from "@/types/test-suites";
import { DATASET_ITEM_SOURCE } from "@/types/datasets";

vi.mock("@/api/datasets/useExperimentById", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  getExperimentById: vi.fn(),
}));

vi.mock("@/api/datasets/useCompareExperimentsList", () => ({
  default: vi.fn(),
}));

vi.mock("@/hooks/usePlaygroundDataset", () => ({
  usePlaygroundDataset: () => ({ datasetId: "suite-id" }),
}));

const makeLLMJudgeEvaluator = (assertions: string[]) => ({
  name: "llm_judge",
  type: MetricType.LLM_AS_JUDGE,
  config: {
    schema: assertions.map((name) => ({
      name,
      type: "BOOLEAN",
      description: "",
    })),
  },
});

const makeRow = (
  overrides: Partial<ExperimentsCompare> = {},
): ExperimentsCompare => ({
  id: "row-1",
  data: {},
  source: DATASET_ITEM_SOURCE.manual,
  created_at: "",
  last_updated_at: "",
  experiment_items: [],
  ...overrides,
});

const makeItem = (
  overrides: Partial<ExperimentsCompare["experiment_items"][0]> = {},
) => ({
  id: "ei-1",
  experiment_id: "exp-1",
  dataset_item_id: "ds-1",
  input: {},
  output: {},
  created_at: "",
  last_updated_at: "",
  ...overrides,
});

describe("areAllRowItemsScored", () => {
  describe("no experiment items", () => {
    it("should return false when items list is empty", () => {
      const row = makeRow({ experiment_items: [] });
      expect(areAllRowItemsScored(row)).toBe(false);
    });
  });

  describe("no evaluators defined", () => {
    it("should return true when evaluators is an empty array", () => {
      const row = makeRow({
        evaluators: [],
        experiment_items: [makeItem()],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });

    it("should fall back to status check when evaluators is undefined", () => {
      const row = makeRow({
        evaluators: undefined,
        experiment_items: [makeItem({ status: "completed" as never })],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });

    it("should return true when evaluators is undefined (no evaluators defined)", () => {
      const row = makeRow({
        evaluators: undefined,
        experiment_items: [makeItem({ status: undefined })],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });
  });

  describe("with assertions", () => {
    const evaluators = [makeLLMJudgeEvaluator(["is_correct", "is_helpful"])];

    it("should return true when all items have enough assertion results", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [
          makeItem({
            assertion_results: [
              { value: "is_correct", passed: true },
              { value: "is_helpful", passed: false },
            ],
          }),
        ],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });

    it("should return false when items have fewer assertion results than expected", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [
          makeItem({
            assertion_results: [{ value: "is_correct", passed: true }],
          }),
        ],
      });
      expect(areAllRowItemsScored(row)).toBe(false);
    });

    it("should return false when assertion_results is undefined", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [makeItem({ assertion_results: undefined })],
      });
      expect(areAllRowItemsScored(row)).toBe(false);
    });

    it("should require all items to be scored", () => {
      const row = makeRow({
        evaluators,
        experiment_items: [
          makeItem({
            id: "ei-1",
            assertion_results: [
              { value: "is_correct", passed: true },
              { value: "is_helpful", passed: true },
            ],
          }),
          makeItem({
            id: "ei-2",
            assertion_results: [],
          }),
        ],
      });
      expect(areAllRowItemsScored(row)).toBe(false);
    });
  });

  describe("for one experiment", () => {
    it("should ignore the items of other experiments", () => {
      const row = makeRow({
        evaluators: [makeLLMJudgeEvaluator(["is_correct"])],
        experiment_items: [
          makeItem({
            id: "ei-1",
            experiment_id: "exp-1",
            assertion_results: [{ value: "is_correct", passed: true }],
          }),
          makeItem({
            id: "ei-2",
            experiment_id: "exp-2",
            assertion_results: [],
          }),
        ],
      });
      expect(areAllRowItemsScored(row, "exp-1")).toBe(true);
      expect(areAllRowItemsScored(row, "exp-2")).toBe(false);
    });
  });

  describe("non-llm-judge evaluator", () => {
    it("should treat non-llm-judge evaluator as zero assertions and check status", () => {
      const row = makeRow({
        evaluators: [{ name: "custom", type: "custom", config: {} }],
        experiment_items: [makeItem({ status: "completed" as never })],
      });
      expect(areAllRowItemsScored(row)).toBe(true);
    });
  });
});

describe("useTestSuitePromptResults", () => {
  const PROMPT_A = "prompt-a";
  const PROMPT_B = "prompt-b";

  let experiments: Record<string, Partial<Experiment>>;

  const completed = (id: string, passRate: number) => ({
    id,
    status: EXPERIMENT_STATUS.COMPLETED,
    pass_rate: passRate,
    passed_count: passRate * 10,
    total_count: 10,
  });

  const result = (passRate: number, isWinner: boolean) => ({
    passRate,
    passedCount: passRate * 10,
    totalCount: 10,
    isWinner,
  });

  const renderResults = () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
    const rendered = renderHook(() => useTestSuitePromptResults(), {
      wrapper,
    });
    return { ...rendered, queryClient };
  };

  beforeEach(() => {
    experiments = {
      "experiment-a1": completed("experiment-a1", 0.5),
      "experiment-b1": completed("experiment-b1", 0.8),
    };
    vi.mocked(getExperimentById).mockImplementation(
      async (_, { experimentId }) => {
        if (!experiments[experimentId]) throw new Error("Not found");
        return experiments[experimentId];
      },
    );
    vi.mocked(useCompareExperimentsList).mockReturnValue({
      data: undefined,
    } as ReturnType<typeof useCompareExperimentsList>);

    const state = usePlaygroundStore.getState();
    state.clearCreatedExperiments();
    state.setDatasetType(DATASET_TYPE.TEST_SUITE);
    state.setExperimentByPromptId({
      [PROMPT_A]: "experiment-a1",
      [PROMPT_B]: "experiment-b1",
    });
  });

  afterEach(() => {
    vi.mocked(getExperimentById).mockReset();
  });

  it("should keep the result of a variant that did not re-run while another variant runs", async () => {
    const { result: hook, queryClient } = renderResults();
    await waitFor(() =>
      expect(hook.current).toEqual({
        [PROMPT_A]: result(0.5, false),
        [PROMPT_B]: result(0.8, true),
      }),
    );

    experiments["experiment-b2"] = {
      id: "experiment-b2",
      status: EXPERIMENT_STATUS.RUNNING,
    };
    act(() => {
      usePlaygroundStore
        .getState()
        .setPromptExperiment(PROMPT_B, "experiment-b2");
    });

    await waitFor(() =>
      expect(
        queryClient.getQueryData([
          "experiment",
          { experimentId: "experiment-b2" },
        ]),
      ).toBeDefined(),
    );
    expect(hook.current).toEqual({ [PROMPT_A]: result(0.5, false) });
  });

  it("should rank a re-run variant only within its own run once it is scored", async () => {
    const { result: hook, queryClient } = renderResults();
    await waitFor(() => expect(hook.current?.[PROMPT_A]).toBeDefined());

    experiments["experiment-b2"] = {
      id: "experiment-b2",
      status: EXPERIMENT_STATUS.RUNNING,
    };
    act(() => {
      usePlaygroundStore
        .getState()
        .setPromptExperiment(PROMPT_B, "experiment-b2");
    });
    await waitFor(() =>
      expect(
        queryClient.getQueryData([
          "experiment",
          { experimentId: "experiment-b2" },
        ]),
      ).toBeDefined(),
    );
    expect(hook.current?.[PROMPT_B]).toBeUndefined();

    experiments["experiment-b2"] = completed("experiment-b2", 0.3);
    await act(() => queryClient.refetchQueries());

    await waitFor(() =>
      expect(hook.current).toEqual({
        [PROMPT_A]: result(0.5, false),
        [PROMPT_B]: result(0.3, true),
      }),
    );
  });

  it("should still rank a variant when an experiment from its older run can no longer be read", async () => {
    experiments["experiment-b2"] = completed("experiment-b2", 0.3);
    delete experiments["experiment-b1"];
    usePlaygroundStore.setState({
      experimentByPromptId: {
        [PROMPT_A]: "experiment-a1",
        [PROMPT_B]: "experiment-b2",
      },
      runExperimentIdsByPromptId: {
        [PROMPT_A]: ["experiment-a1", "experiment-b1"],
        [PROMPT_B]: ["experiment-b2"],
      },
    });

    const { result: hook } = renderResults();

    await waitFor(() =>
      expect(hook.current).toEqual({
        [PROMPT_A]: result(0.5, true),
        [PROMPT_B]: result(0.3, true),
      }),
    );
  });
});
