import { createEnumParam } from "use-query-params";

export const DetailsActionSection = {
  Annotations: "annotations",
  Comments: "comments",
  AIAssistants: "ai_assistants",
  AgentOptimizer: "agent-optimizer",
} as const;
export type DetailsActionSectionValue =
  (typeof DetailsActionSection)[keyof typeof DetailsActionSection];

export const DetailsActionSectionParam =
  createEnumParam<DetailsActionSectionValue>([
    DetailsActionSection.Annotations,
    DetailsActionSection.Comments,
    DetailsActionSection.AIAssistants,
    DetailsActionSection.AgentOptimizer,
  ]);
