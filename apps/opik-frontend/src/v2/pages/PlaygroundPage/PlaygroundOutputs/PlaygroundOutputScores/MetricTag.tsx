import React from "react";
import { Link } from "@tanstack/react-router";
import { Loader2 } from "lucide-react";

import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import ColorIndicator from "@/shared/ColorIndicator/ColorIndicator";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { ScoreData } from "./PlaygroundOutputScores";

interface MetricTagProps {
  metricName: string;
  color: string;
  score?: ScoreData;
  ruleId?: string;
  waitOver?: boolean;
  notRun?: boolean;
}

const NoScore: React.FC<{ ruleId?: string }> = ({ ruleId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const dash = <span className="comet-body-s text-muted-slate">&mdash;</span>;

  if (!ruleId) {
    return <TooltipWrapper content="No score arrived.">{dash}</TooltipWrapper>;
  }

  return (
    <TooltipWrapper content="No score arrived. Click to open the rule's execution logs, which may say why.">
      <Link
        to="/$workspaceName/automation-logs"
        params={{ workspaceName }}
        search={{ rule_id: ruleId }}
        target="_blank"
        onClick={(event) => event.stopPropagation()}
        aria-label="Open the rule's execution logs"
      >
        {dash}
      </Link>
    </TooltipWrapper>
  );
};

const MetricTag: React.FC<MetricTagProps> = ({
  metricName,
  color,
  score,
  ruleId,
  waitOver = false,
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

  const renderState = () => {
    if (notRun) {
      return <span className="comet-body-s text-muted-slate">&mdash;</span>;
    }
    if (waitOver) {
      return <NoScore ruleId={ruleId} />;
    }
    return <Loader2 className="size-3 animate-spin text-muted-slate" />;
  };

  return (
    <div className="flex h-6 items-center gap-1.5 rounded-md border border-border px-2">
      <ColorIndicator label={metricName} color={color} variant="square" />
      <span className="comet-body-s-accented truncate text-muted-slate">
        {metricName}
      </span>
      {renderState()}
    </div>
  );
};

export default MetricTag;
