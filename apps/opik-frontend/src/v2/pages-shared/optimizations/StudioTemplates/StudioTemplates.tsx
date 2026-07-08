import React from "react";
import { ArrowRight } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import {
  ACCENT_CARD_STYLES,
  type AccentCardStyle,
} from "@/constants/accentCardStyles";
import useNavigateToOptimizationStudio from "@/v2/pages-shared/optimizations/useNavigateToOptimizationStudio";
import {
  getStudioCardConfigs,
  type StudioCardId,
} from "@/v2/pages-shared/optimizations/studioCards";

type StudioTemplatesProps = {
  onOptimizeViaSdkClick: () => void;
};

// Per-card CTA copy + shared accent palette; icon and routing come from the
// shared getStudioCardConfigs.
const CARD_CONFIG: Record<
  StudioCardId,
  { style: AccentCardStyle; actionLabel: string }
> = {
  demo: { style: ACCENT_CARD_STYLES[0], actionLabel: "Try template" },
  studio: { style: ACCENT_CARD_STYLES[1], actionLabel: "Create optimization" },
  sdk: { style: ACCENT_CARD_STYLES[2], actionLabel: "View SDK guide" },
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
        const { style, actionLabel } = CARD_CONFIG[id];
        return (
          // Card is a plain clickable container (not a <button>) so it can hold
          // a real Button CTA; the CTA has no handler of its own — its click
          // bubbles up to this onClick.
          <div
            key={id}
            role="button"
            tabIndex={0}
            onClick={onClick}
            className={cn(
              "flex cursor-pointer items-start gap-2 rounded-md border px-3 pb-2 pt-3 text-left shadow-sm transition-colors",
              style.card,
            )}
          >
            <span
              className={cn(
                "flex shrink-0 items-center justify-center rounded-md p-[7px]",
                style.iconBg,
              )}
            >
              <Icon className="size-3.5 text-[#030712]" />
            </span>
            <span className="flex min-w-0 flex-1 flex-col items-start gap-px">
              <span className="comet-body-s-accented w-full truncate text-foreground">
                {title}
              </span>
              <span className="comet-body-xs text-muted-slate">
                {description}
              </span>
              <Button variant="link" size="2xs" tabIndex={-1} className="pl-0">
                {actionLabel}
                <ArrowRight className="size-3" />
              </Button>
            </span>
          </div>
        );
      })}
    </div>
  );
};

export default StudioTemplates;
