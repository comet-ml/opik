import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import useRunExperimentExecution from "./useRunExperimentExecution";
import api from "@/api/api";
import { PlaygroundPromptType } from "@/types/playground";

vi.mock("@/api/api", () => ({
  default: { post: vi.fn() },
  EXPERIMENT_EXECUTION_REST_ENDPOINT: "/v1/private/experiments/execute",
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

vi.mock("@tanstack/react-query", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@tanstack/react-query")>();
  return {
    ...actual,
    useMutation: vi.fn(
      (options: { mutationFn: (vars: unknown) => Promise<unknown> }) => ({
        mutateAsync: options.mutationFn,
      }),
    ),
  };
});

const postMock = vi.mocked(api.post);

const buildPrompt = (): PlaygroundPromptType =>
  ({
    id: "prompt-1",
    model: "gpt-4",
    messages: [{ id: "m1", role: "user", content: "Hello" }],
    configs: {},
  }) as unknown as PlaygroundPromptType;

const runWith = async (
  overrides: Record<string, unknown> = {},
): Promise<Record<string, unknown>> => {
  const { result } = renderHook(() => useRunExperimentExecution());

  await result.current.mutateAsync({
    datasetName: "my-dataset",
    datasetId: "dataset-1",
    prompts: [buildPrompt()],
    ...overrides,
  } as never);

  return postMock.mock.calls[0][1] as Record<string, unknown>;
};

describe("useRunExperimentExecution", () => {
  beforeEach(() => {
    postMock.mockReset();
    postMock.mockResolvedValue({
      data: { experiments: [], total_items: 0 },
    } as never);
  });

  it("sends the picked rule ids so the backend run is scored by them", async () => {
    const body = await runWith({ selectedRuleIds: ["rule-1", "rule-2"] });

    expect(body.selected_rule_ids).toEqual(["rule-1", "rule-2"]);
  });

  it("passes an empty selection through untouched", async () => {
    const body = await runWith({ selectedRuleIds: [] });

    expect(body.selected_rule_ids).toEqual([]);
  });

  it("sends the table's filters so the run covers the same rows the user sees", async () => {
    const filters = '[{"field":"data","operator":"contains","value":"x"}]';
    const body = await runWith({ filters });

    expect(body.filters).toBe(filters);
  });

  it("leaves filters out when the table is unfiltered", async () => {
    const body = await runWith({ filters: undefined });

    expect(body.filters).toBeUndefined();
  });
});
