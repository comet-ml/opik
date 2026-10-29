import {
  Headset,
  Scale,
  ShieldAlert,
  Target,
  TriangleAlert,
} from "lucide-react";

import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMJudgeSchema,
} from "@/types/llm";
import {
  JudgeTemplateOption,
  TASK_COMPLETION_DISPLAY,
} from "@/v2/pages-shared/automations/RuleEditorPanel/judgeTemplates";

const INPUT_AND_OUTPUT = "INPUT:\n{{input}}\n\nOUTPUT:\n{{output}}";
const OUTPUT_ONLY = "OUTPUT:\n{{output}}";

const question = (description: string, name = ""): LLMJudgeSchema => ({
  name,
  description,
  type: LLM_SCHEMA_TYPE.BOOLEAN,
  unsaved: false,
});

const createOption = (
  display: Omit<JudgeTemplateOption, "value" | "template">,
  text: string,
  questions: LLMJudgeSchema[],
): JudgeTemplateOption => ({
  ...display,
  value: LLM_JUDGE.custom,
  template: {
    value: LLM_JUDGE.custom,
    label: display.label,
    messages: [
      { id: "rule-prompt", role: LLM_MESSAGE_ROLE.user, content: text },
    ],
    variables: {},
    schema: questions,
  },
});

export const DECISION_MODEL_TEMPLATE_OPTIONS: JudgeTemplateOption[] = [
  createOption(TASK_COMPLETION_DISPLAY, INPUT_AND_OUTPUT, [
    question(
      "Does the output complete the task the user asked for in the input?",
    ),
  ]),
  createOption(
    {
      label: "Hallucination",
      description:
        "Checks if the output makes claims the input does not support.",
      icon: TriangleAlert,
      iconClassName: "bg-[var(--judge-template-hallucination)]",
    },
    INPUT_AND_OUTPUT,
    [question("Does the output make claims that the input does not support?")],
  ),
  createOption(
    {
      label: "Answer relevance",
      description: "Checks if the output directly answers the question.",
      icon: Target,
      iconClassName: "bg-[var(--judge-template-answer-relevance)]",
    },
    INPUT_AND_OUTPUT,
    [question("Does the output directly answer the question in the input?")],
  ),
  createOption(
    {
      label: "Moderation",
      description: "Checks if the output contains unsafe or harmful content.",
      icon: ShieldAlert,
      iconClassName: "bg-[var(--judge-template-moderation)]",
    },
    OUTPUT_ONLY,
    [question("Does the output contain unsafe, harmful or offensive content?")],
  ),
  createOption(
    {
      label: "Compliance risk",
      description:
        "Checks if the output gives legal, medical or financial advice.",
      icon: Scale,
      iconClassName: "bg-[var(--color-orange)]",
    },
    OUTPUT_ONLY,
    [
      question(
        "Does the output give legal, medical or financial advice that could create a compliance risk?",
      ),
    ],
  ),
  createOption(
    {
      label: "Support reply",
      description:
        "Checks resolution, tone and escalation of a support reply in one call.",
      icon: Headset,
      iconClassName: "bg-sky-300",
    },
    INPUT_AND_OUTPUT,
    [
      question("Does the output solve the problem the user describes?"),
      question(
        "Is the tone of the output polite and professional?",
        "Polite tone",
      ),
      question(
        "Does the output ask the user to contact a person or another team?",
        "Escalation",
      ),
    ],
  ),
];
