import React from "react";
import { Loader2 } from "lucide-react";

import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import ColorIndicator from "@/shared/ColorIndicator/ColorIndicator";
import { ScoreData } from "./PlaygroundOutputScores";

interface MetricTagProps {
  metricName: string;
  color: string;
  score?: ScoreData;
  notRun?: boolean;
}

const MetricTag: React.FC<MetricTagProps> = ({
  metricName,
  color,
  score,
  notRun = false,
}) => {
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
      {notRun ? (
        <span className="comet-body-s text-muted-slate">&mdash;</span>
      ) : (
        <Loader2 className="size-3 animate-spin text-muted-slate" />
      )}
    </div>
  );
};

export default MetricTag;
