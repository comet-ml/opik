import {
  BotMessageSquare,
  FileSliders,
  SquareDashedMousePointer,
  type LucideIcon,
} from "lucide-react";

export type StudioCardId = "demo" | "studio" | "sdk";

export type StudioCardConfig = {
  id: StudioCardId;
  icon: LucideIcon;
  title: string;
  description: string;
  onClick: () => void;
};

type StudioCardHandlers = {
  navigateToStudio: (templateId?: string) => void;
  onOptimizeViaSdkClick: () => void;
};

// Single definition of the three optimization onboarding cards (demo / studio /
// SDK) shared by the populated runs-list row (StudioTemplates) and the empty
// state (OptimizationsEmptyState) so their copy, icon, and routing can't drift.
// Each view keeps its own visual treatment, keyed by `id`.
export const getStudioCardConfigs = ({
  navigateToStudio,
  onOptimizeViaSdkClick,
}: StudioCardHandlers): StudioCardConfig[] => [
  {
    id: "demo",
    icon: BotMessageSquare,
    title: "Run a demo example",
    description:
      "Start with a pre-configured optimization example for a support chatbot.",
    onClick: () => navigateToStudio("opik-chatbot"),
  },
  {
    id: "studio",
    icon: SquareDashedMousePointer,
    title: "Start an optimization run",
    description:
      "Create a custom optimization workflow to test and improve your prompts.",
    onClick: () => navigateToStudio(),
  },
  {
    id: "sdk",
    icon: FileSliders,
    title: "Optimize via SDK",
    description:
      "Generate starter code for running a custom optimization programmatically.",
    onClick: onOptimizeViaSdkClick,
  },
];
