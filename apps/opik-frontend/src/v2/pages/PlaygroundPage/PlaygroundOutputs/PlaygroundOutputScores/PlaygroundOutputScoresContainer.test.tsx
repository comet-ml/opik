import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
} from "@/types/automations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import PlaygroundOutputScoresContainer from "./PlaygroundOutputScoresContainer";

const TRACE_ID = "trace-1";
const PROJECT_ID = "project-1";

type RulesState = {
  content: EvaluatorsRule[];
  total: number;
  isSuccess: boolean;
  isError: boolean;
};

let rulesState: RulesState;
let traceScores: { name: string; value: number }[];
let traceQueryOptions: {
  enabled: boolean;
  refetchInterval: (query: {
    state: { data?: { feedback_scores: { name: string }[] } };
  }) => number | false;
};

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "ws" })),
  useActiveProjectId: () => PROJECT_ID,
}));

vi.mock("@/api/automations/useRulesList", () => ({
  default: () => ({
    data: { content: rulesState.content, total: rulesState.total },
    isSuccess: rulesState.isSuccess,
    isError: rulesState.isError,
  }),
}));

vi.mock("@/api/traces/useTraceById", () => ({
  default: (_params: unknown, options: typeof traceQueryOptions) => {
    traceQueryOptions = options;
    return { data: { feedback_scores: traceScores } };
  },
}));

vi.mock("./PlaygroundOutputScores", () => ({
  default: ({ metricNames }: { metricNames: string[] }) => (
    <div data-testid="metric-names">{metricNames.join(",")}</div>
  ),
}));

const makeRule = (
  id: string,
  scoreNames: string[],
  overrides: Partial<EvaluatorsRule> = {},
): EvaluatorsRule =>
  ({
    id,
    name: `rule-${id}`,
    sampling_rate: 1,
    enabled: true,
    trigger_scope: EVAL_TRIGGER_SCOPE.production,
    created_at: "",
    created_by: "",
    last_updated_at: "",
    last_updated_by: "",
    type: EVALUATORS_RULE_TYPE.llm_judge,
    code: {
      model: { name: PROVIDER_MODEL_TYPE.GPT_4O },
      messages: [],
      variables: {},
      schema: scoreNames.map((name) => ({
        name,
        type: "INTEGER",
        description: "",
      })),
    },
    ...overrides,
  }) as EvaluatorsRule;

const renderContainer = (selectedRuleIds: string[] | null) =>
  render(
    <PlaygroundOutputScoresContainer
      traceId={TRACE_ID}
      selectedRuleIds={selectedRuleIds}
    />,
  );

const pollWith = (receivedNames: string[]) =>
  traceQueryOptions.refetchInterval({
    state: {
      data: { feedback_scores: receivedNames.map((name) => ({ name })) },
    },
  });

beforeEach(() => {
  rulesState = { content: [], total: 0, isSuccess: true, isError: false };
  traceScores = [];
});

describe("PlaygroundOutputScoresContainer", () => {
  describe("polling gate", () => {
    it("should not query the trace when no rule scores it", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 1;

      renderContainer(null);

      expect(traceQueryOptions.enabled).toBe(false);
    });

    it("should query the trace while the rules list is still loading", () => {
      rulesState.isSuccess = false;

      renderContainer(null);

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it("should stop querying the trace when the rules lookup fails", () => {
      rulesState.isSuccess = false;
      rulesState.isError = true;

      renderContainer(null);

      expect(traceQueryOptions.enabled).toBe(false);
    });

    it("should query the trace when the rules list holds more than one page", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 120;

      renderContainer(null);

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it("should query the trace for a rule the user picked, whatever its scope", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
          enabled: false,
        }),
      ];
      rulesState.total = 1;

      renderContainer(["r1"]);

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it("should query the trace for an enabled rule that targets experiments", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
        }),
      ];
      rulesState.total = 1;

      renderContainer(null);

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it("should ignore a thread rule, whose scores never reach the trace", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          type: EVALUATORS_RULE_TYPE.thread_llm_judge,
          trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
        }),
      ];
      rulesState.total = 1;

      renderContainer(null);

      expect(traceQueryOptions.enabled).toBe(false);
    });
  });

  describe("refetch interval", () => {
    it("should stop once every awaited score has arrived", () => {
      rulesState.content = [makeRule("r1", ["Relevance", "Accuracy"])];
      rulesState.total = 1;

      renderContainer(["r1"]);

      expect(pollWith(["Relevance"])).toBe(5000);
      expect(pollWith(["Relevance", "Accuracy"])).toBe(false);
    });

    it("should keep polling when the rules list holds more than one page", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 120;

      renderContainer(["r1"]);

      expect(pollWith(["Relevance"])).toBe(5000);
    });

    it("should keep polling when no score name could be predicted", () => {
      rulesState.content = [
        makeRule("r1", [], { trigger_scope: EVAL_TRIGGER_SCOPE.experiment }),
      ];
      rulesState.total = 1;

      renderContainer(null);

      expect(pollWith([])).toBe(5000);
    });
  });

  describe("rendered metrics", () => {
    it("should show a score that no picked rule announced", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 1;
      traceScores = [{ name: "Hallucination", value: 1 }];

      renderContainer(["r1"]);

      expect(screen.getByTestId("metric-names")).toHaveTextContent(
        "Hallucination,Relevance",
      );
    });

    it("should show no metric when nothing is picked and nothing scored", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 1;

      renderContainer(null);

      expect(screen.getByTestId("metric-names")).toHaveTextContent("");
    });
  });
});
