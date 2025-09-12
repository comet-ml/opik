import React, { useCallback, useMemo, useState } from "react";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Button } from "@/components/ui/button";
import { X } from "lucide-react";
import FeedbackScoresHistogram from "../FeedbackScoresHistogram/FeedbackScoresHistogram";
import FeedbackScoresPieChart from "../FeedbackScoresPieChart/FeedbackScoresPieChart";
import { Experiment } from "@/types/datasets";
import { Filter } from "@/types/filters";
import { COLUMN_FEEDBACK_SCORES_ID, COLUMN_TYPE } from "@/types/shared";

interface FeedbackScoresDistributionChartsProps {
  experiments: Experiment[];
  filters: Filter[];
  onFiltersChange: (filters: Filter[]) => void;
}

const FeedbackScoresDistributionCharts: React.FC<
  FeedbackScoresDistributionChartsProps
> = ({ experiments, filters, onFiltersChange }) => {
  const [activeScore, setActiveScore] = useState<string | null>(null);
  const [activeRange, setActiveRange] = useState<{
    start: number;
    end: number;
  } | null>(null);

  // Get unique feedback score names from experiments
  const scoreNames = useMemo(() => {
    const names = new Set<string>();
    experiments.forEach((experiment) => {
      experiment.feedback_scores?.forEach((score) => {
        names.add(score.name);
      });
    });
    return Array.from(names).sort();
  }, [experiments]);

  const handleRangeClick = useCallback(
    (rangeStart: number, rangeEnd: number) => {
      if (!activeScore) return;

      // Create two filters for the range: >= rangeStart and <= rangeEnd
      const minFilter: Filter = {
        id: `${activeScore}-min`,
        field: `${COLUMN_FEEDBACK_SCORES_ID}.${activeScore}`,
        type: COLUMN_TYPE.number,
        operator: ">=",
        value: rangeStart,
      };

      const maxFilter: Filter = {
        id: `${activeScore}-max`,
        field: `${COLUMN_FEEDBACK_SCORES_ID}.${activeScore}`,
        type: COLUMN_TYPE.number,
        operator: "<=",
        value: rangeEnd,
      };

      // Remove existing filters for this score if they exist
      const existingFilters = filters.filter(
        (f) =>
          !f.field.startsWith(`${COLUMN_FEEDBACK_SCORES_ID}.${activeScore}`),
      );

      onFiltersChange([...existingFilters, minFilter, maxFilter]);
      setActiveRange({ start: rangeStart, end: rangeEnd });
    },
    [activeScore, filters, onFiltersChange],
  );

  const clearActiveFilter = useCallback(() => {
    if (!activeScore) return;

    const newFilters = filters.filter(
      (f) => !f.field.startsWith(`${COLUMN_FEEDBACK_SCORES_ID}.${activeScore}`),
    );
    onFiltersChange(newFilters);
    setActiveRange(null);
  }, [activeScore, filters, onFiltersChange]);

  const clearAllFilters = useCallback(() => {
    const newFilters = filters.filter(
      (f) => !f.field.startsWith(`${COLUMN_FEEDBACK_SCORES_ID}.`),
    );
    onFiltersChange(newFilters);
    setActiveRange(null);
  }, [filters, onFiltersChange]);

  // Check if there are any active score filters
  const hasActiveFilters = useMemo(() => {
    return filters.some((f) =>
      f.field.startsWith(`${COLUMN_FEEDBACK_SCORES_ID}.`),
    );
  }, [filters]);

  if (scoreNames.length === 0) {
    return null;
  }

  return (
    <div className="mb-6">
      <div className="mb-4 flex items-center justify-between">
        <h2 className="comet-title-m">Feedback Scores Distribution</h2>
        {hasActiveFilters && (
          <Button
            variant="outline"
            size="sm"
            onClick={clearAllFilters}
            className="flex items-center gap-2"
          >
            <X className="size-3.5" />
            Clear all filters
          </Button>
        )}
      </div>

      <div className="mb-4">
        <label className="comet-body-s-accented mb-2 block">
          Select metric:
        </label>
        <div className="flex flex-wrap gap-2">
          {scoreNames.map((scoreName) => {
            const isActive = activeScore === scoreName;
            const hasFilter = filters.some((f) =>
              f.field.startsWith(`${COLUMN_FEEDBACK_SCORES_ID}.${scoreName}`),
            );

            return (
              <Button
                key={scoreName}
                variant={isActive ? "default" : "outline"}
                size="sm"
                onClick={() => setActiveScore(scoreName)}
                className={hasFilter ? "ring-2 ring-primary" : ""}
              >
                {scoreName}
                {hasFilter && <span className="ml-1">●</span>}
              </Button>
            );
          })}
        </div>
      </div>

      {activeScore && (
        <div
          className="mb-4"
          style={{ "--chart-height": "240px" } as React.CSSProperties}
        >
          <Tabs defaultValue="histogram" className="w-full">
            <TabsList className="grid w-full grid-cols-2">
              <TabsTrigger value="histogram">Histogram</TabsTrigger>
              <TabsTrigger value="pie">Pie Chart</TabsTrigger>
            </TabsList>
            <TabsContent value="histogram" className="mt-4">
              <FeedbackScoresHistogram
                name={`${activeScore} Distribution`}
                experiments={experiments}
                scoreName={activeScore}
                onRangeClick={handleRangeClick}
              />
            </TabsContent>
            <TabsContent value="pie" className="mt-4">
              <FeedbackScoresPieChart
                name={`${activeScore} Distribution`}
                experiments={experiments}
                scoreName={activeScore}
                onRangeClick={handleRangeClick}
              />
            </TabsContent>
          </Tabs>
        </div>
      )}

      {activeRange && (
        <div className="rounded-md border border-primary/20 bg-primary/5 p-3">
          <div className="flex items-center justify-between">
            <div className="comet-body-s">
              Filtered to traces with <strong>{activeScore}</strong> between{" "}
              <strong>{activeRange.start.toFixed(2)}</strong> and{" "}
              <strong>{activeRange.end.toFixed(2)}</strong>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={clearActiveFilter}
              className="size-6 p-0"
            >
              <X className="size-3" />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
};

export default FeedbackScoresDistributionCharts;
