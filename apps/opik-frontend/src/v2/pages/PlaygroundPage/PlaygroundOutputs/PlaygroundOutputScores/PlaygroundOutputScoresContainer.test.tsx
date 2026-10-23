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

type TraceData = {
  feedback_scores: { name: string; value?: number }[];
  metadata: Record<string, unknown>;
};

let rulesState: RulesState;
let traceData: TraceData | undefined;
let pickerRuleIds: string[] | null;
let traceQueryOptions: {
  enabled: boolean;
  refetchInterval: (query: { state: { data?: TraceData } }) => number | false;
};

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "ws" })),
  useActiveProjectId: () => PROJECT_ID,
}));

vi.mock("@/store/PlaygroundStore", () => ({
  useSelectedRuleIds: () => pickerRuleIds,
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
    return { data: traceData };
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

const makeTrace = (
  pickedRuleIds: string[] | null,
  receivedNames: string[] = [],
): TraceData => ({
  feedback_scores: receivedNames.map((name) => ({ name, value: 1 })),
  metadata: {
    created_from: "playground",
    ...(pickedRuleIds && { selected_rule_ids: pickedRuleIds }),
  },
});

const renderContainer = () =>
  render(<PlaygroundOutputScoresContainer traceId={TRACE_ID} />);

const pollWith = (trace: TraceData | undefined) =>
  traceQueryOptions.refetchInterval({ state: { data: trace } });

beforeEach(() => {
  rulesState = { content: [], total: 0, isSuccess: true, isError: false };
  traceData = undefined;
  pickerRuleIds = null;
});

describe("PlaygroundOutputScoresContainer", () => {
  describe("polling gate", () => {
    it("should not query the trace when the project has no rule", () => {
      renderContainer();

      expect(traceQueryOptions.enabled).toBe(false);
    });

    it("should query the trace while the rules list is still loading", () => {
      rulesState.isSuccess = false;

      renderContainer();

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it("should stop querying the trace when the rules lookup fails", () => {
      rulesState.isSuccess = false;
      rulesState.isError = true;

      renderContainer();

      expect(traceQueryOptions.enabled).toBe(false);
    });

    it("should query the trace when the rules list holds more than one page", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          type: EVALUATORS_RULE_TYPE.span_llm_judge,
        }),
      ];
      rulesState.total = 120;

      renderContainer();

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it("should query the trace for a production rule, since the run may have picked it", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
          enabled: false,
        }),
      ];
      rulesState.total = 1;

      renderContainer();

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it("should query the trace for an enabled rule that targets experiments", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
        }),
      ];
      rulesState.total = 1;

      renderContainer();

      expect(traceQueryOptions.enabled).toBe(true);
    });

    it.each([
      ["thread", EVALUATORS_RULE_TYPE.thread_llm_judge],
      ["span", EVALUATORS_RULE_TYPE.span_llm_judge],
    ])(
      "should ignore a %s rule, whose scores never reach the trace",
      (_, type) => {
        rulesState.content = [
          makeRule("r1", ["Relevance"], {
            type,
            trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
          }),
        ];
        rulesState.total = 1;

        renderContainer();

        expect(traceQueryOptions.enabled).toBe(false);
      },
    );
  });

  describe("refetch interval", () => {
    it("should stop once every score of the rule picked for the trace has arrived", () => {
      rulesState.content = [makeRule("r1", ["Relevance", "Accuracy"])];
      rulesState.total = 1;

      renderContainer();

      expect(pollWith(makeTrace(["r1"], ["Relevance"]))).toBe(5000);
      expect(pollWith(makeTrace(["r1"], ["Relevance", "Accuracy"]))).toBe(
        false,
      );
    });

    it("should keep polling for a picked production rule after the always-run scores arrive", () => {
      rulesState.content = [
        makeRule("r1", ["Helpfulness"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
        }),
        makeRule("r2", ["Test"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 2;

      renderContainer();

      expect(pollWith(makeTrace(["r2"], ["Helpfulness"]))).toBe(5000);
      expect(pollWith(makeTrace(["r2"], ["Helpfulness", "Test"]))).toBe(false);
    });

    it("should keep polling while the trace has not loaded, since its picks are unknown", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 1;

      renderContainer();

      expect(pollWith(undefined)).toBe(5000);
    });

    it("should stop once the trace loads and no rule scores it", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 1;

      renderContainer();

      expect(pollWith(makeTrace(null))).toBe(false);
    });

    it("should await a picked rule even when it is disabled, as the backend still scores it", () => {
      rulesState.content = [makeRule("r1", ["Relevance"], { enabled: false })];
      rulesState.total = 1;

      renderContainer();

      expect(pollWith(makeTrace(["r1"]))).toBe(5000);
      expect(pollWith(makeTrace(["r1"], ["Relevance"]))).toBe(false);
    });

    it("should not await a disabled experiment rule nobody picked", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
          enabled: false,
        }),
      ];
      rulesState.total = 1;

      renderContainer();

      expect(pollWith(makeTrace(null))).toBe(false);
    });

    it("should ignore a picked rule that no longer exists", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 1;

      renderContainer();

      expect(pollWith(makeTrace(["deleted-rule"]))).toBe(false);
    });

    it("should read malformed picks as none", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 1;

      renderContainer();

      expect(
        pollWith({
          feedback_scores: [],
          metadata: { selected_rule_ids: "r1" },
        }),
      ).toBe(false);
    });

    it("should stop on the trace's picks, not the picker's", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 1;
      pickerRuleIds = ["r1"];

      renderContainer();

      expect(pollWith(makeTrace(null))).toBe(false);
    });

    it("should keep polling while the rules list is still loading", () => {
      rulesState.isSuccess = false;

      renderContainer();

      expect(pollWith(makeTrace(["r1"]))).toBe(5000);
    });

    it("should keep polling when the rules list holds more than one page", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 120;

      renderContainer();

      expect(pollWith(makeTrace(["r1"], ["Relevance"]))).toBe(5000);
    });

    it("should keep polling when no score name could be predicted", () => {
      rulesState.content = [
        makeRule("r1", [], { trigger_scope: EVAL_TRIGGER_SCOPE.experiment }),
      ];
      rulesState.total = 1;

      renderContainer();

      expect(pollWith(makeTrace(null))).toBe(5000);
    });
  });

  describe("rendered metrics", () => {
    it("should show a pending metric for the rule picked for the trace, whatever its scope", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 1;
      traceData = makeTrace(["r1"]);

      renderContainer();

      expect(screen.getByTestId("metric-names")).toHaveTextContent("Relevance");
    });

    it("should show a pending metric for a rule picked in the picker before the trace loads", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 1;
      pickerRuleIds = ["r1"];

      renderContainer();

      expect(screen.getByTestId("metric-names")).toHaveTextContent("Relevance");
    });

    it("should replace the picker's picks with the trace's once it loads", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.production,
        }),
      ];
      rulesState.total = 1;
      pickerRuleIds = ["r1"];
      traceData = makeTrace(null);

      renderContainer();

      expect(screen.getByTestId("metric-names")).toHaveTextContent("");
    });

    it("should show a score that no picked rule announced", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 1;
      traceData = makeTrace(["r1"], ["Hallucination"]);

      renderContainer();

      expect(screen.getByTestId("metric-names")).toHaveTextContent(
        "Hallucination,Relevance",
      );
    });

    it("should show no metric when nothing is picked and nothing scored", () => {
      rulesState.content = [makeRule("r1", ["Relevance"])];
      rulesState.total = 1;
      traceData = makeTrace(null);

      renderContainer();

      expect(screen.getByTestId("metric-names")).toHaveTextContent("");
    });

    it.each([
      ["thread", EVALUATORS_RULE_TYPE.thread_llm_judge],
      ["span", EVALUATORS_RULE_TYPE.span_llm_judge],
    ])(
      "should not show a pending metric for a picked %s rule, whose scores never reach the trace",
      (_, type) => {
        rulesState.content = [makeRule("r1", ["Relevance"], { type })];
        rulesState.total = 1;
        traceData = makeTrace(["r1"]);

        renderContainer();

        expect(screen.getByTestId("metric-names")).toHaveTextContent("");
      },
    );

    it("should show a pending metric for an unpicked rule that targets experiments before the trace loads", () => {
      rulesState.content = [
        makeRule("r1", ["Relevance"], {
          trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
        }),
      ];
      rulesState.total = 1;

      renderContainer();

      expect(screen.getByTestId("metric-names")).toHaveTextContent("Relevance");
    });
  });
});
