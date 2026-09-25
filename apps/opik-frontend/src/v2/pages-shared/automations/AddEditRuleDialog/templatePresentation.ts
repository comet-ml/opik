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
import { IconBadgeColor } from "@/shared/IconBadge/IconBadge";

type TemplatePresentation = {
  Icon: React.ComponentType<{ className?: string }>;
  color: IconBadgeColor;
};

const DEFAULT_PRESENTATION: TemplatePresentation = {
  Icon: Sparkles,
  color: "gray",
};

const PRESENTATION: Partial<Record<LLM_JUDGE, TemplatePresentation>> = {
  [LLM_JUDGE.hallucination]: { Icon: TriangleAlert, color: "blue" },
  [LLM_JUDGE.moderation]: { Icon: ShieldAlert, color: "pink" },
  [LLM_JUDGE.answer_relevance]: { Icon: Target, color: "green" },
  [LLM_JUDGE.structure_compliance]: { Icon: ListChecks, color: "yellow" },
  [LLM_JUDGE.meaning_match]: { Icon: BadgeCheck, color: "turquoise" },
  [LLM_JUDGE.conversational_coherence]: {
    Icon: MessagesSquare,
    color: "purple",
  },
  [LLM_JUDGE.user_frustration]: { Icon: Frown, color: "orange" },
};

export const getTemplatePresentation = (
  template: LLM_JUDGE | string,
): TemplatePresentation =>
  PRESENTATION[template as LLM_JUDGE] ?? DEFAULT_PRESENTATION;
