import { act, renderHook } from "@testing-library/react";
import { useForm } from "react-hook-form";
import { describe, expect, it } from "vitest";

import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import useRuleEditorHistory from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleEditorHistory";

const press = (key: string, shiftKey = false) =>
  act(() => {
    window.dispatchEvent(
      new KeyboardEvent("keydown", { key, metaKey: true, shiftKey }),
    );
  });

describe("useRuleEditorHistory", () => {
  it("undoes typing one word at a time", () => {
    const { result } = renderHook(() => {
      const form = useForm<EvaluationRuleFormType>({
        defaultValues: {
          ruleName: "",
          uiType: UI_EVALUATORS_RULE_TYPE.llm_judge,
          llmJudgeDetails: { messages: [], schema: [] },
        } as unknown as EvaluationRuleFormType,
      });
      const history = useRuleEditorHistory(form, { current: "" });
      return { form, history };
    });
    const ruleName = () => result.current.form.getValues("ruleName");

    ["M", "My", "My ", "My r", "My ru", "My rul", "My ru"].forEach((value) =>
      act(() => result.current.form.setValue("ruleName", value)),
    );

    press("z");
    expect(ruleName()).toBe("My rul");
    press("z");
    expect(ruleName()).toBe("My ");
    press("z");
    expect(ruleName()).toBe("");
  });

  it("undoes typing and actions in order, and redoes them", () => {
    const { result } = renderHook(() => {
      const form = useForm<EvaluationRuleFormType>({
        defaultValues: {
          ruleName: "",
          uiType: UI_EVALUATORS_RULE_TYPE.llm_judge,
          llmJudgeDetails: { messages: [], schema: [] },
        } as unknown as EvaluationRuleFormType,
      });
      const history = useRuleEditorHistory(form, { current: "" });
      return { form, history };
    });
    const ruleName = () => result.current.form.getValues("ruleName");

    act(() => result.current.form.setValue("ruleName", "My rule"));
    act(() =>
      result.current.history.recordAction(() => {
        result.current.form.setValue("ruleName", "Hallucination");
        result.current.history.setIsPickingTemplate(true);
      }),
    );
    act(() =>
      result.current.history.recordAction(() =>
        result.current.history.setIsPickingTemplate(false),
      ),
    );

    press("z");
    expect(ruleName()).toBe("Hallucination");
    expect(result.current.history.isPickingTemplate).toBe(true);

    press("z");
    expect(ruleName()).toBe("My rule");
    expect(result.current.history.isPickingTemplate).toBe(false);

    press("z");
    expect(ruleName()).toBe("");

    press("z", true);
    press("z", true);
    expect(ruleName()).toBe("Hallucination");
    expect(result.current.history.isPickingTemplate).toBe(true);
  });
});
