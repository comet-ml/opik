import {
  BadgeCheck,
  Frown,
  ListChecks,
  MessagesSquare,
  ShieldAlert,
  Sparkles,
  Target,
  TriangleAlert,
} from "lucide-react";

import { LLM_JUDGE } from "@/types/llm";
import { TagProps } from "@/ui/tag";

type TemplatePresentation = {
  Icon: React.ComponentType<{ className?: string }>;
  variant: NonNullable<TagProps["variant"]>;
};

const DEFAULT_PRESENTATION: TemplatePresentation = {
  Icon: Sparkles,
  variant: "gray",
};

const PRESENTATION: Partial<Record<LLM_JUDGE, TemplatePresentation>> = {
  [LLM_JUDGE.hallucination]: { Icon: TriangleAlert, variant: "blue" },
  [LLM_JUDGE.moderation]: { Icon: ShieldAlert, variant: "pink" },
  [LLM_JUDGE.answer_relevance]: { Icon: Target, variant: "green" },
  [LLM_JUDGE.structure_compliance]: { Icon: ListChecks, variant: "yellow" },
  [LLM_JUDGE.meaning_match]: { Icon: BadgeCheck, variant: "turquoise" },
  [LLM_JUDGE.conversational_coherence]: {
    Icon: MessagesSquare,
    variant: "purple",
  },
  [LLM_JUDGE.user_frustration]: { Icon: Frown, variant: "orange" },
};

export const getTemplatePresentation = (
  template: LLM_JUDGE | string,
): TemplatePresentation =>
  PRESENTATION[template as LLM_JUDGE] ?? DEFAULT_PRESENTATION;
