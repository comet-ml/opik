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

// Shared definition of the onboarding cards; each view styles them by `id`.
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
    title: "Use the Optimization studio",
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
