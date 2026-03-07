import React from "react";
import { CellContext } from "@tanstack/react-table";

type FlattenedTrialItem = {
  experimentItem: {
    feedback_scores?: { name: string; value: number }[];
  };
  allRuns: {
    feedback_scores?: { name: string; value: number }[];
  }[];
  executionPolicy?: {
    runs_per_item?: number;
    pass_threshold?: number;
  };
};

const TrialPassedCell: React.FC<CellContext<FlattenedTrialItem, unknown>> = (
  context,
) => {
  const row = context.row.original;
  const { allRuns, executionPolicy } = row;

  const hasScores = allRuns.some(
    (r) => r.feedback_scores && r.feedback_scores.length > 0,
  );

  if (!hasScores) {
    return <span>-</span>;
  }

  const passThreshold = executionPolicy?.pass_threshold ?? 1;
  const totalRuns = allRuns.length;

  const runsPassed = allRuns.filter((run) => {
    const scores = run.feedback_scores;
    if (!scores?.length) return true;
    return scores.every((s) => s.value >= 1.0);
  }).length;

  const itemPassed = runsPassed >= passThreshold;

  if (totalRuns === 1) {
    return (
      <span className={itemPassed ? "text-success" : "text-destructive"}>
        {itemPassed ? "Yes" : "No"}
      </span>
    );
  }

  return (
    <span className={itemPassed ? "text-success" : "text-destructive"}>
      {runsPassed}/{totalRuns} (threshold: {passThreshold})
    </span>
  );
};

export default TrialPassedCell;
