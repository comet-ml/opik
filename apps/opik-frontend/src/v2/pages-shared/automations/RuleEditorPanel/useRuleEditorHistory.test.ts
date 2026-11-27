import { useEffect, useRef } from "react";
import { act, renderHook } from "@testing-library/react";
import { useForm } from "react-hook-form";
import { describe, expect, it } from "vitest";

import { UI_EVALUATORS_RULE_TYPE } from "@/types/automations";
import { LLM_SCHEMA_TYPE } from "@/types/llm";
import { EvaluationRuleFormType } from "@/v2/pages-shared/automations/RuleEditorPanel/schema";
import useRuleEditorHistory from "@/v2/pages-shared/automations/RuleEditorPanel/useRuleEditorHistory";

const press = (key: string, shiftKey = false) =>
  act(() => {
    window.dispatchEvent(
      new KeyboardEvent("keydown", { key, metaKey: true, shiftKey }),
    );
  });

const renderHistory = ({ syncMainScoreName = false } = {}) => {
  const { result } = renderHook(() => {
    const form = useForm<EvaluationRuleFormType>({
      defaultValues: {
        ruleName: "",
        samplingRate: 1,
        uiType: UI_EVALUATORS_RULE_TYPE.llm_judge,
        llmJudgeDetails: {
          model: "",
          config: { temperature: 0 },
          messages: [],
          schema: [
            { name: "", type: LLM_SCHEMA_TYPE.BOOLEAN, description: "" },
          ],
        },
      } as unknown as EvaluationRuleFormType,
    });
    useEffect(() => {
      if (!syncMainScoreName) return;
      const subscription = form.watch((values, { name }) => {
        if (name === "ruleName") {
          form.setValue("llmJudgeDetails.schema.0.name", values.ruleName ?? "");
        }
      });
      return () => subscription.unsubscribe();
    }, [form]);
    const autoNameRef = useRef("");
    const history = useRuleEditorHistory(form, autoNameRef);
    return { form, history };
  });
  const type = async (...values: string[]) => {
    for (const value of values) {
      await act(async () => result.current.form.setValue("ruleName", value));
    }
  };
  return {
    result,
    type,
    ruleName: () => result.current.form.getValues("ruleName"),
  };
};

describe("useRuleEditorHistory", () => {
  it("undoes typing one word at a time", async () => {
    const { type, ruleName } = renderHistory();

    await type("M", "My", "My ", "My r", "My ru", "My rul", "My ru");

    press("z");
    expect(ruleName()).toBe("My rul");
    press("z");
    expect(ruleName()).toBe("My ");
    press("z");
    expect(ruleName()).toBe("");
  });

  it("ignores the nested update that copies the name into the main score", async () => {
    const { type, ruleName } = renderHistory({ syncMainScoreName: true });

    await type("a", "ab", "a", "");

    press("z");
    expect(ruleName()).toBe("ab");
    press("z");
    expect(ruleName()).toBe("");
  });

  it("keeps an action as one step when a silent update runs inside it", async () => {
    const { result, type, ruleName } = renderHistory();

    await type("My rule ");
    act(() =>
      result.current.history.recordAction(() => {
        result.current.history.withoutHistory(() =>
          result.current.form.setValue("llmJudgeDetails.schema.0.name", "x"),
        );
        result.current.form.setValue("ruleName", "Hallucination");
      }),
    );

    press("z");
    expect(ruleName()).toBe("My rule ");
    press("z");
    expect(ruleName()).toBe("");
  });

  it("undoes typing and actions in order, and redoes them", async () => {
    const { result, type, ruleName } = renderHistory();

    await type("My rule");
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

  it("undoes a slider drag as one step and a multi-field change as one step", async () => {
    const { result } = renderHistory();
    const { form } = result.current;

    for (const rate of [0.9, 0.85, 0.8]) {
      await act(async () => form.setValue("samplingRate", rate));
    }
    await act(async () => {
      form.setValue("llmJudgeDetails.model", "gpt-5" as never);
      form.setValue("llmJudgeDetails.config", { temperature: 1 } as never);
    });

    press("z");
    expect(form.getValues("llmJudgeDetails.model")).toBe("");
    expect(form.getValues("llmJudgeDetails.config")).toEqual({
      temperature: 0,
    });
    expect(form.getValues("samplingRate")).toBe(0.8);
    press("z");
    expect(form.getValues("samplingRate")).toBe(1);
  });
});
