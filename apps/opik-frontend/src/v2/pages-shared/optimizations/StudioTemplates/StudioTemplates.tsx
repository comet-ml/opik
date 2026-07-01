import React from "react";
import { ArrowRight } from "lucide-react";

import { Button } from "@/ui/button";
import useNavigateToOptimizationStudio from "@/v2/pages-shared/optimizations/useNavigateToOptimizationStudio";
import {
  getStudioCardConfigs,
  type StudioCardId,
} from "@/v2/pages-shared/optimizations/studioCards";

type StudioTemplatesProps = {
  onOptimizeViaSdkClick: () => void;
};

// Per-card visual treatment (icon chip color + card tint); copy, icon and
// routing come from the shared getStudioCardConfigs.
const CARD_STYLES: Record<
  StudioCardId,
  { chipColor: string; tintBg: string; tintBorder: string; actionLabel: string }
> = {
  demo: {
    chipColor: "#89deff",
    tintBg: "rgba(186, 230, 253, 0.1)",
    tintBorder: "rgba(186, 230, 253, 0.6)",
    actionLabel: "Try template",
  },
  studio: {
    chipColor: "#a78bfa",
    tintBg: "rgba(196, 181, 253, 0.1)",
    tintBorder: "rgba(196, 181, 253, 0.4)",
    actionLabel: "Create optimization",
  },
  sdk: {
    chipColor: "#e25af6",
    tintBg: "rgba(240, 171, 252, 0.1)",
    tintBorder: "rgba(240, 171, 252, 0.5)",
    actionLabel: "View SDK guide",
  },
};

const StudioTemplates: React.FC<StudioTemplatesProps> = ({
  onOptimizeViaSdkClick,
}) => {
  const navigateToStudio = useNavigateToOptimizationStudio();
  const cards = getStudioCardConfigs({
    navigateToStudio,
    onOptimizeViaSdkClick,
  });

  return (
    <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
      {cards.map(({ id, icon: Icon, title, description, onClick }) => {
        const { chipColor, tintBg, tintBorder, actionLabel } = CARD_STYLES[id];
        return (
          <Button
            key={id}
            variant="outline"
            onClick={onClick}
            className="h-auto items-start justify-start gap-2 whitespace-normal px-3 pb-2 pt-3 text-left shadow-sm transition-shadow hover:shadow-md"
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
          </Button>
        );
      })}
    </div>
  );
};

export default StudioTemplates;
