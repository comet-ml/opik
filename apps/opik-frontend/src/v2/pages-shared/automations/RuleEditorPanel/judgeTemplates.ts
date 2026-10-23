import {
  Frown,
  MessagesSquare,
  ShieldAlert,
  Target,
  TriangleAlert,
  Wrench,
} from "lucide-react";

import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { LLM_JUDGE, LLMPromptTemplate } from "@/types/llm";
import { LLM_PROMPT_TEMPLATES } from "@/constants/llm";
import { MenuOption } from "@/shared/MenuOptionItems/MenuOptionItems";

type JudgeTemplateDisplay = Omit<MenuOption<LLM_JUDGE>, "value">;

const JUDGE_TEMPLATE_DISPLAY: Partial<Record<LLM_JUDGE, JudgeTemplateDisplay>> =
  {
    [LLM_JUDGE.hallucination]: {
      label: "Hallucination",
      description: "Checks if the response includes unsupported information.",
      icon: TriangleAlert,
      iconClassName: "bg-[var(--judge-template-hallucination)]",
    },
    [LLM_JUDGE.moderation]: {
      label: "Moderation",
      description: "Checks if the response contains unsafe or harmful content.",
      icon: ShieldAlert,
      iconClassName: "bg-[var(--judge-template-moderation)]",
    },
    [LLM_JUDGE.answer_relevance]: {
      label: "Answer relevance",
      description:
        "Checks if the response directly answers the user’s question.",
      icon: Target,
      iconClassName: "bg-[var(--judge-template-answer-relevance)]",
    },
    [LLM_JUDGE.tool_correctness]: {
      label: "Tool correctness",
      description:
        "Checks if the agent called the right tools and used their results correctly.",
      icon: Wrench,
      iconClassName: "bg-[var(--judge-template-tool-correctness)]",
    },
    [LLM_JUDGE.conversational_coherence]: {
      label: "Conversational coherence",
      description: "Checks if each reply fits the conversation so far.",
      icon: MessagesSquare,
      iconClassName: "bg-[var(--judge-template-answer-relevance)]",
    },
    [LLM_JUDGE.user_frustration]: {
      label: "User frustration",
      description: "Checks if the user gets frustrated in the conversation.",
      icon: Frown,
      iconClassName: "bg-[var(--judge-template-moderation)]",
    },
  };

export type JudgeTemplateOption = MenuOption<LLM_JUDGE> & {
  template: LLMPromptTemplate;
};

export const getJudgeTemplateOptions = (
  scope: EVALUATORS_RULE_SCOPE,
): JudgeTemplateOption[] =>
  LLM_PROMPT_TEMPLATES[scope].flatMap((template) => {
    const display = JUDGE_TEMPLATE_DISPLAY[template.value];
    return display ? [{ ...display, value: template.value, template }] : [];
  });
