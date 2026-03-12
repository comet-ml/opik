import React from "react";
import { Loader2 } from "lucide-react";

import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import ColorIndicator from "@/components/shared/ColorIndicator/ColorIndicator";
import { ScoreData } from "./PlaygroundOutputScores";

interface MetricTagProps {
  metricName: string;
  color: string;
  score?: ScoreData;
}

const MetricTag: React.FC<MetricTagProps> = ({ metricName, color, score }) => {
  if (score) {
    return (
      <FeedbackScoreTag
        label={metricName}
        value={score.value}
        reason={score.reason}
        lastUpdatedAt={score.lastUpdatedAt}
        lastUpdatedBy={score.lastUpdatedBy}
        valueByAuthor={score.valueByAuthor}
        category={score.category}
      />
    );
  }

  return (
    <div className="flex h-6 items-center gap-1.5 rounded-md border border-border px-2">
      <ColorIndicator label={metricName} color={color} variant="square" />
      <span className="comet-body-s-accented truncate text-muted-slate">
        {metricName}
      </span>
      <Loader2 className="size-3 animate-spin text-muted-slate" />
    </div>
  );
};

export default MetricTag;
