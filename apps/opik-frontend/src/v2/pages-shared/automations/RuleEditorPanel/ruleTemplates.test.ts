import { describe, expect, it } from "vitest";

import { findSelectedTemplate } from "@/v2/pages-shared/automations/RuleEditorPanel/ruleTemplates";
import { DECISION_MODEL_TEMPLATE_OPTIONS } from "@/v2/pages-shared/automations/RuleEditorPanel/decisionModelTemplates";
import { getJudgeTemplateOptions } from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";
import {
  EVALUATORS_RULE_SCOPE,
  UI_EVALUATORS_RULE_TYPE,
} from "@/types/automations";

describe("findSelectedTemplate", () => {
  const supportReply = DECISION_MODEL_TEMPLATE_OPTIONS.find(
    (option) => option.label === "Support reply",
  )!;

  it("matches a template whatever the main score name, and stops matching after an edit", () => {
    const content = {
      messages: supportReply.template.messages,
      schema: supportReply.template.schema.map((question, index) =>
        index === 0 ? { ...question, name: "My rule" } : question,
      ),
    };

    expect(
      findSelectedTemplate(
        content,
        DECISION_MODEL_TEMPLATE_OPTIONS,
        UI_EVALUATORS_RULE_TYPE.decision_model,
      ),
    ).toBe(supportReply);

    expect(
      findSelectedTemplate(
        { ...content, schema: content.schema.slice(0, 2) },
        DECISION_MODEL_TEMPLATE_OPTIONS,
        UI_EVALUATORS_RULE_TYPE.decision_model,
      ),
    ).toBeUndefined();
  });

  it("still matches a judge template after the save clears the main score description", () => {
    const options = getJudgeTemplateOptions(EVALUATORS_RULE_SCOPE.trace);
    const hallucination = options.find(
      (option) => option.label === "Hallucination",
    )!;
    const [mainScore, ...scores] = hallucination.template.schema;

    expect(
      findSelectedTemplate(
        {
          messages: hallucination.template.messages,
          schema: [{ ...mainScore, description: "" }, ...scores],
        },
        options,
        UI_EVALUATORS_RULE_TYPE.llm_judge,
      ),
    ).toBe(hallucination);
  });
});
