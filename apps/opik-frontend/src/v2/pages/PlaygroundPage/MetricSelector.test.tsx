import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render as renderComponent,
  screen,
  fireEvent,
} from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import {
  EVAL_TRIGGER_SCOPE,
  EVALUATORS_RULE_TYPE,
  EvaluatorsRule,
} from "@/types/automations";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({
    permissions: { canUpdateOnlineEvaluationRules: false },
  }),
}));

vi.mock(
  "@/v2/pages-shared/automations/AddEditRuleDialog/AddEditRuleDialog",
  () => ({
    default: () => null,
  }),
);

import MetricSelector from "./MetricSelector";

const render = (ui: React.ReactElement) =>
  renderComponent(<TooltipProvider>{ui}</TooltipProvider>);

const onSelectionChange = vi.fn();

const makeRule = (
  id: string,
  overrides: Partial<EvaluatorsRule> = {},
): EvaluatorsRule =>
  ({
    id,
    name: id,
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
      schema: [],
    },
    ...overrides,
  }) as EvaluatorsRule;

const renderSelector = (
  rules: EvaluatorsRule[],
  selectedRuleIds: string[] | null = null,
) =>
  render(
    <MetricSelector
      rules={rules}
      selectedRuleIds={selectedRuleIds}
      onSelectionChange={onSelectionChange}
      projectId="p1"
      canUsePlayground
      open
      onOpenChange={vi.fn()}
    />,
  );

describe("MetricSelector", () => {
  beforeEach(() => onSelectionChange.mockClear());

  it("should list only trace rules, since thread and span rules cannot score a playground run", () => {
    renderSelector([
      makeRule("trace-judge"),
      makeRule("trace-python", { type: EVALUATORS_RULE_TYPE.python_code }),
      makeRule("thread-rule", {
        type: EVALUATORS_RULE_TYPE.thread_llm_judge,
      }),
      makeRule("span-rule", { type: EVALUATORS_RULE_TYPE.span_llm_judge }),
    ]);

    expect(screen.getByText("trace-judge")).toBeInTheDocument();
    expect(screen.getByText("trace-python")).toBeInTheDocument();
    expect(screen.queryByText("thread-rule")).not.toBeInTheDocument();
    expect(screen.queryByText("span-rule")).not.toBeInTheDocument();
  });

  it("should toggle a production-scoped rule on click", () => {
    renderSelector([makeRule("pickable")]);

    fireEvent.click(screen.getByText("pickable"));

    expect(onSelectionChange).toHaveBeenCalledWith(["pickable"]);
  });

  it.each([
    ["experiment", EVAL_TRIGGER_SCOPE.experiment],
    ["both", EVAL_TRIGGER_SCOPE.both],
  ])(
    "should show a %s-scoped rule as checked and not toggle it on click",
    (_, trigger_scope) => {
      renderSelector([makeRule("always-run", { trigger_scope })]);

      const checkbox = screen.getByRole("checkbox", { name: "" });
      expect(checkbox).toBeDisabled();
      expect(checkbox).toBeChecked();

      fireEvent.click(screen.getByText("always-run"));

      expect(onSelectionChange).not.toHaveBeenCalled();
    },
  );

  it("should treat a disabled experiment-scoped rule as pickable, since it only runs when picked", () => {
    renderSelector([
      makeRule("disabled-experiment", {
        trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
        enabled: false,
      }),
    ]);

    fireEvent.click(screen.getByText("disabled-experiment"));

    expect(onSelectionChange).toHaveBeenCalledWith(["disabled-experiment"]);
  });

  it("should count always-run rules as selected alongside the picked ones", () => {
    renderSelector(
      [
        makeRule("picked"),
        makeRule("always-run", {
          trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
        }),
        makeRule("untouched"),
      ],
      ["picked"],
    );

    expect(screen.getByText("2 of 3 selected")).toBeInTheDocument();
  });

  it("should select only the toggleable rules, leaving always-run ones out of the selection", () => {
    renderSelector([
      makeRule("pickable"),
      makeRule("always-run", { trigger_scope: EVAL_TRIGGER_SCOPE.both }),
    ]);

    fireEvent.click(screen.getByText("1 of 2 selected"));

    expect(onSelectionChange).toHaveBeenCalledWith(["pickable"]);
  });

  it("should hide the select-all row when every listed rule always runs", () => {
    renderSelector([
      makeRule("always-run", {
        trigger_scope: EVAL_TRIGGER_SCOPE.experiment,
      }),
    ]);

    expect(screen.queryByText(/selected$/)).not.toBeInTheDocument();
  });
});
