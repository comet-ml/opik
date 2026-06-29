import React from "react";
import {
  ArrowRight,
  BotMessageSquare,
  FileSliders,
  SquareDashedMousePointer,
  type LucideIcon,
} from "lucide-react";

import useNavigateToOptimizationStudio from "@/v2/pages-shared/optimizations/useNavigateToOptimizationStudio";

type StudioTemplatesProps = {
  onOptimizeViaSdkClick: () => void;
};

type StudioCard = {
  icon: LucideIcon;
  // CSS color token driving the icon, its chip tint, the card tint and the link
  color: string;
  title: string;
  description: string;
  actionLabel: string;
  onClick: () => void;
};

const StudioTemplates: React.FC<StudioTemplatesProps> = ({
  onOptimizeViaSdkClick,
}) => {
  const navigateToStudio = useNavigateToOptimizationStudio();

  // Three onboarding cards, matching the Figma runs-list (562:37189).
  const cards: StudioCard[] = [
    {
      icon: BotMessageSquare,
      color: "var(--chart-blue)",
      title: "Run a demo example",
      description:
        "Start with a pre-configured optimization example for a support chatbot.",
      actionLabel: "Try template",
      onClick: () => navigateToStudio("opik-chatbot"),
    },
    {
      icon: SquareDashedMousePointer,
      color: "var(--chart-purple)",
      title: "Use the Optimization studio",
      description:
        "Create a custom optimization workflow to test and improve your prompts.",
      actionLabel: "Create optimization",
      onClick: () => navigateToStudio(),
    },
    {
      icon: FileSliders,
      color: "var(--chart-burgundy)",
      title: "Optimize via SDK",
      description:
        "Generate starter code for running a custom optimization programmatically.",
      actionLabel: "View SDK guide",
      onClick: onOptimizeViaSdkClick,
    },
  ];

  return (
    <div className="pt-4">
      <h2 className="comet-title-s sticky top-0 z-10 truncate break-words bg-soft-background pb-3 pt-2">
        Run an optimization
      </h2>
      <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {cards.map(
          ({ icon: Icon, color, title, description, actionLabel, onClick }) => (
            <button
              key={title}
              type="button"
              onClick={onClick}
              className="flex flex-col items-start gap-2 rounded-md border border-border p-4 text-left transition-colors hover:border-primary"
              style={{
                backgroundColor: `color-mix(in srgb, ${color} 5%, transparent)`,
              }}
            >
              <span
                className="flex size-7 shrink-0 items-center justify-center rounded-md"
                style={{
                  backgroundColor: `color-mix(in srgb, ${color} 14%, transparent)`,
                }}
              >
                <Icon className="size-4" style={{ color }} />
              </span>
              <span className="comet-body-s-accented text-foreground">
                {title}
              </span>
              <span className="comet-body-xs text-muted-slate">
                {description}
              </span>
              <span
                className="comet-body-xs mt-1 inline-flex items-center gap-1 font-medium"
                style={{ color }}
              >
                {actionLabel}
                <ArrowRight className="size-3" />
              </span>
            </button>
          ),
        )}
      </div>
    </div>
  );
};

export default StudioTemplates;
