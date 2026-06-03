import React from "react";
import { ArrowRight, Lightbulb, TrendingUp } from "lucide-react";
import { Card } from "@/ui/card";
import { Button } from "@/ui/button";
import { Recommendation, RecommendationSeverity } from "../types";

const SEVERITY_STYLES: Record<
  RecommendationSeverity,
  { dot: string; label: string; text: string }
> = {
  high: {
    dot: "bg-chart-red",
    label: "High impact",
    text: "text-chart-red",
  },
  medium: {
    dot: "bg-chart-yellow",
    label: "Medium impact",
    text: "text-chart-yellow",
  },
  low: {
    dot: "bg-chart-green",
    label: "Low impact",
    text: "text-chart-green",
  },
};

const fmt = (n: number) =>
  `$${n.toLocaleString(undefined, { maximumFractionDigits: 0 })}`;

interface Props {
  recommendations: Recommendation[];
}

export const RecommendationsSection: React.FC<Props> = ({
  recommendations,
}) => {
  const totalSavings = recommendations.reduce(
    (a, r) => a + r.estSavingsMonthly,
    0,
  );
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-end justify-between">
        <div>
          <div className="comet-body-accented text-foreground">
            Recommendations
          </div>
          <div className="comet-body-xs text-muted-foreground">
            Concrete next actions ranked by estimated monthly savings, based on
            the composition above.
          </div>
        </div>
        <div className="text-right">
          <div className="comet-body-xs text-muted-foreground">
            Potential savings / month
          </div>
          <div className="comet-title-m text-chart-green">
            {fmt(totalSavings)}
          </div>
        </div>
      </div>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-3">
        {recommendations.map((rec) => {
          const sev = SEVERITY_STYLES[rec.severity];
          return (
            <Card key={rec.id} className="flex flex-col gap-3 p-4">
              <div className="flex items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span
                    className={`inline-block size-2 rounded-full ${sev.dot}`}
                  />
                  <span className={`comet-body-xs ${sev.text}`}>
                    {sev.label}
                  </span>
                </div>
                <div className="flex items-center gap-1 text-chart-green">
                  <TrendingUp className="size-3" />
                  <span className="comet-body-s-accented">
                    {fmt(rec.estSavingsMonthly)}/mo
                  </span>
                </div>
              </div>
              <div className="flex flex-col gap-1">
                <div className="comet-body-s-accented text-foreground">
                  {rec.title}
                </div>
                <div className="comet-body-xs text-muted-foreground">
                  {rec.body}
                </div>
              </div>
              <div className="mt-auto flex justify-between">
                <div className="flex items-center gap-1 text-muted-foreground">
                  <Lightbulb className="size-3" />
                  <span className="comet-body-xs">Suggested</span>
                </div>
                <Button variant="link" size="sm" className="h-auto p-0">
                  {rec.actionLabel}
                  <ArrowRight className="ml-1 size-3" />
                </Button>
              </div>
            </Card>
          );
        })}
      </div>
    </div>
  );
};
