import React from "react";
import { Link } from "@tanstack/react-router";
import { CircleAlert, CircleMinus, Loader2, RefreshCw } from "lucide-react";

import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import ColorIndicator from "@/shared/ColorIndicator/ColorIndicator";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useAppStore from "@/store/AppStore";
import { ONLINE_SCORING_STATE, OnlineScoringStatus } from "@/types/automations";
import { ScoreData } from "./PlaygroundOutputScores";
import { scoringStatusMessage } from "./scoringStatus";

const LOGS_HINT = "Click to open the rule's execution logs.";

interface MetricTagProps {
  metricName: string;
  color: string;
  score?: ScoreData;
  ruleId?: string;
  status?: OnlineScoringStatus;
  waitOver?: boolean;
  notRun?: boolean;
}

const RuleLogsLink: React.FC<{
  ruleId?: string;
  tooltip: string;
  label: string;
  children: React.ReactNode;
}> = ({ ruleId, tooltip, label, children }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  if (!ruleId) {
    return <TooltipWrapper content={tooltip}>{children}</TooltipWrapper>;
  }

  return (
    <TooltipWrapper content={`${tooltip} ${LOGS_HINT}`}>
      <Link
        to="/$workspaceName/automation-logs"
        params={{ workspaceName }}
        search={{ rule_id: ruleId }}
        target="_blank"
        onClick={(event) => event.stopPropagation()}
        aria-label={label}
      >
        {children}
      </Link>
    </TooltipWrapper>
  );
};

const STATUS_ICON = {
  [ONLINE_SCORING_STATE.RETRYING]: {
    Icon: RefreshCw,
    className: "size-3 text-chart-yellow",
    label: "Scoring is retrying",
  },
  [ONLINE_SCORING_STATE.FAILED]: {
    Icon: CircleAlert,
    className: "size-3 text-destructive",
    label: "Scoring failed",
  },
  [ONLINE_SCORING_STATE.SKIPPED]: {
    Icon: CircleMinus,
    className: "size-3 text-muted-slate",
    label: "Scoring skipped",
  },
};

const MetricTag: React.FC<MetricTagProps> = ({
  metricName,
  color,
  score,
  ruleId,
  status,
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
    // A state this build doesn't know, from a newer backend, falls through as if there were no status.
    const statusIcon = status && STATUS_ICON[status.state];
    if (status && statusIcon) {
      const { Icon, className, label } = statusIcon;
      return (
        <RuleLogsLink
          ruleId={status.rule_id}
          tooltip={scoringStatusMessage(status)}
          label={label}
        >
          <Icon className={className} />
        </RuleLogsLink>
      );
    }
    if (waitOver) {
      return (
        <RuleLogsLink
          ruleId={ruleId}
          tooltip="No score arrived."
          label="Open the rule's execution logs"
        >
          <span className="comet-body-s text-muted-slate">&mdash;</span>
        </RuleLogsLink>
      );
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
