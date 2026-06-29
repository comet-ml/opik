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
  chipColor: string; // solid icon-chip background
  tintBg: string; // card background tint
  tintBorder: string; // card border tint
  title: string;
  description: string;
  actionLabel: string;
  onClick: () => void;
};

const StudioTemplates: React.FC<StudioTemplatesProps> = ({
  onOptimizeViaSdkClick,
}) => {
  const navigateToStudio = useNavigateToOptimizationStudio();

  // Three onboarding cards, matching the Figma runs-list (562:37189 / 686:35206):
  // a solid colored icon chip + title/description + a primary-blue link, on a
  // lightly-tinted card.
  const cards: StudioCard[] = [
    {
      icon: BotMessageSquare,
      chipColor: "#89deff",
      tintBg: "rgba(186, 230, 253, 0.1)",
      tintBorder: "rgba(186, 230, 253, 0.6)",
      title: "Run a demo example",
      description:
        "Start with a pre-configured optimization example for a support chatbot.",
      actionLabel: "Try template",
      onClick: () => navigateToStudio("opik-chatbot"),
    },
    {
      icon: SquareDashedMousePointer,
      chipColor: "#a78bfa",
      tintBg: "rgba(196, 181, 253, 0.1)",
      tintBorder: "rgba(196, 181, 253, 0.4)",
      title: "Use the Optimization studio",
      description:
        "Create a custom optimization workflow to test and improve your prompts.",
      actionLabel: "Create optimization",
      onClick: () => navigateToStudio(),
    },
    {
      icon: FileSliders,
      chipColor: "#e25af6",
      tintBg: "rgba(240, 171, 252, 0.1)",
      tintBorder: "rgba(240, 171, 252, 0.5)",
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
      <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
        {cards.map(
          ({
            icon: Icon,
            chipColor,
            tintBg,
            tintBorder,
            title,
            description,
            actionLabel,
            onClick,
          }) => (
            <button
              key={title}
              type="button"
              onClick={onClick}
              className="flex items-start gap-2 rounded-md border px-3 pb-2 pt-3 text-left shadow-sm transition-shadow hover:shadow-md"
              style={{ backgroundColor: tintBg, borderColor: tintBorder }}
            >
              <span
                className="flex shrink-0 items-center justify-center rounded-md p-[7px]"
                style={{ backgroundColor: chipColor }}
              >
                <Icon className="size-3.5 text-white" />
              </span>
              <span className="flex min-w-0 flex-1 flex-col items-start gap-px">
                <span className="comet-body-s-accented w-full truncate text-foreground">
                  {title}
                </span>
                <span className="comet-body-xs text-muted-slate">
                  {description}
                </span>
                <span className="comet-body-xs mt-1 inline-flex items-center gap-0.5 text-primary">
                  {actionLabel}
                  <ArrowRight className="size-3" />
                </span>
              </span>
            </button>
          ),
        )}
      </div>
    </div>
  );
};

export default StudioTemplates;
