import React from "react";
import useAiSpendRecommendations from "@/api/ai-spend/useAiSpendRecommendations";
import { Skeleton } from "@/ui/skeleton";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import useSavingsPricer from "@/api/ai-spend/useSavingsPricer";
import RecommendationCard from "./RecommendationCard";

interface AiUsageRecommendationsProps {
  projectName: string;
  intervalStart: string;
  intervalEnd: string;
  userUuid?: string;
  onHoverRecommendation?: (laneKey: string | null) => void;
  className?: string;
}

const AiUsageRecommendations: React.FC<AiUsageRecommendationsProps> = ({
  projectName,
  intervalStart,
  intervalEnd,
  userUuid,
  onHoverRecommendation,
  className,
}) => {
  const priceSavings = useSavingsPricer({
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  });
  const { data, isPending } = useAiSpendRecommendations({
    projectName,
    intervalStart,
    intervalEnd,
    userUuid,
  });
  const items = data?.items ?? [];

  if (isPending) {
    return (
      <div className={cn("flex flex-col gap-2", className)}>
        <Skeleton className="h-5 w-40" />
        {[0, 1].map((i) => (
          <Skeleton key={i} className="h-20 w-full" />
        ))}
      </div>
    );
  }

  if (items.length === 0) {
    return null;
  }

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-col">
          <h2 className="comet-body-accented text-foreground">
            Potential savings
          </h2>
          <span className="comet-body-xs text-muted-slate">
            Ranked by estimated savings, based on current usage
          </span>
        </div>
        <div className="flex flex-col items-end">
          <span className="comet-body-xs text-muted-slate">
            Potential savings
          </span>
          <span className="comet-body-s-accented text-foreground">
            {formatCost(priceSavings(data?.total_savings_tokens))}
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-2">
        {items.map((rec) => (
          <RecommendationCard
            key={rec.id}
            recommendation={rec}
            estSavingUsd={priceSavings(rec.est_saving_tokens)}
            variant="full"
            onHover={onHoverRecommendation}
          />
        ))}
      </div>
    </div>
  );
};

export default AiUsageRecommendations;
