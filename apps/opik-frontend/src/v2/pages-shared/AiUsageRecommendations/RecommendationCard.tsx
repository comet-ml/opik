import React from "react";
import { ArrowUpRight, Lightbulb } from "lucide-react";
import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import { cn } from "@/lib/utils";
import { formatCost } from "@/lib/money";
import { getLaneMeta } from "@/v2/pages-shared/AiUsageBreakdown/laneRegistry";
import { SpendRecommendation } from "@/api/ai-spend/useAiSpendRecommendations";

export type RecommendationCardVariant = "full" | "compact";

const IMPACT: Record<string, { label: string; color: string }> = {
  high: { label: "High impact", color: "#ef4444" },
  medium: { label: "Medium impact", color: "#f59e0b" },
  low: { label: "Low impact", color: "#94a3b8" },
};

interface RecommendationCardProps {
  recommendation: SpendRecommendation;
  variant?: RecommendationCardVariant;
  onHover?: (laneKey: string | null) => void;
  // USD, priced by the parent at the window's blended per-token rate
  // (savings arrive token-denominated from the BE).
  estSavingUsd?: number | null;
}

const RecommendationCard: React.FC<RecommendationCardProps> = ({
  recommendation: rec,
  variant = "full",
  onHover,
  estSavingUsd,
}) => {
  const full = variant === "full";
  const Icon = getLaneMeta(rec.related_lane_key ?? "").icon;
  const impact = IMPACT[(rec.impact ?? "").toLowerCase()] ?? IMPACT.low;

  return (
    <div
      className={cn(
        "flex rounded-md border bg-background",
        full ? "gap-4 px-4 py-3" : "flex-col gap-2 p-2",
      )}
      onMouseEnter={
        onHover ? () => onHover(rec.related_lane_key ?? null) : undefined
      }
      onMouseLeave={onHover ? () => onHover(null) : undefined}
    >
      {full && (
        <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary-foreground text-foreground">
          <Icon className="size-4" />
        </div>
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2">
            {!full && (
              <Lightbulb className="size-3.5 shrink-0 text-light-slate" />
            )}
            <span className="comet-body-xs-accented truncate text-foreground">
              {rec.title}
            </span>
          </div>
          {full ? (
            <span className="comet-body-xs-accented inline-flex shrink-0 items-center gap-1 rounded-md border bg-primary-foreground px-1.5 py-0.5 text-foreground">
              <span
                className="size-1.5 rounded-full"
                style={{ backgroundColor: impact.color }}
              />
              {impact.label}
            </span>
          ) : (
            estSavingUsd != null && (
              <Tag variant="gray" size="sm" className="shrink-0">
                Save {formatCost(estSavingUsd)}
              </Tag>
            )
          )}
        </div>

        <div className={cn("flex gap-4", full ? "items-start" : "flex-col")}>
          <p
            className={cn(
              "comet-body-xs flex-1",
              full ? "text-foreground" : "text-muted-slate",
            )}
          >
            {rec.body}
          </p>
          {full && estSavingUsd != null && (
            <div className="flex shrink-0 flex-col items-end text-right">
              <span className="comet-body-xs-accented text-foreground">
                {formatCost(estSavingUsd)}
              </span>
              <span className="comet-body-xs text-muted-slate">
                est. saving
              </span>
            </div>
          )}
        </div>

        {rec.docs_url && (
          <Button
            variant="link"
            size="sm"
            className="comet-body-xs inline-flex h-auto w-fit gap-0.5 px-0"
            asChild
          >
            <a href={rec.docs_url} target="_blank" rel="noopener noreferrer">
              Read docs
              <ArrowUpRight className="size-3" />
            </a>
          </Button>
        )}
      </div>
    </div>
  );
};

export default RecommendationCard;
