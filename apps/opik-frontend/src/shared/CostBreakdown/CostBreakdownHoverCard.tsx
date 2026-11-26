import React from "react";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/ui/hover-card";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import CostBreakdownContent from "./CostBreakdownContent";
import { CostBreakdownTarget, costOfTarget } from "./types";

type CostBreakdownHoverCardProps = {
  target?: CostBreakdownTarget;
  tooltip: string;
  children: React.ReactElement;
};

const CostBreakdownHoverCard: React.FC<CostBreakdownHoverCardProps> = ({
  target,
  tooltip,
  children,
}) => {
  const isModelPricesEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.MODEL_PRICES_ENABLED,
  );

  if (!isModelPricesEnabled || !target || costOfTarget(target) <= 0) {
    return <TooltipWrapper content={tooltip}>{children}</TooltipWrapper>;
  }

  return (
    <HoverCard openDelay={400}>
      <HoverCardTrigger asChild>{children}</HoverCardTrigger>
      <HoverCardContent
        side="bottom"
        align="start"
        collisionPadding={16}
        className="flex max-h-[min(70vh,var(--radix-hover-card-content-available-height))] w-[460px] max-w-[calc(100vw-32px)] flex-col p-0"
        onClick={(event) => event.stopPropagation()}
      >
        <CostBreakdownContent target={target} />
      </HoverCardContent>
    </HoverCard>
  );
};

export default CostBreakdownHoverCard;
