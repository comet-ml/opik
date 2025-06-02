import React from "react";
import { Span, Trace } from "@/types/traces";
import { LastSectionValue } from "../TraceDetailsPanel";
import LastSectionLayout from "../LastSectionLayout";
import FeedbackScoresEditor from "../../FeedbackScoresEditor/FeedbackScoresEditor";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";

type TraceAnnotateViewerProps = {
  data: Trace | Span;
  spanId?: string;
  traceId: string;
  lastSection?: LastSectionValue | null;
  setLastSection: (v: LastSectionValue | null) => void;
};

const TraceAnnotateViewer: React.FunctionComponent<
  TraceAnnotateViewerProps
> = ({ data, spanId, traceId, lastSection, setLastSection }) => {
  const hasFeedbackScores = Boolean(data.feedback_scores?.length);
  return (
    <LastSectionLayout
      title="Feedback scores"
      closeTooltipContent="Close annotate"
      setLastSection={setLastSection}
      lastSection={lastSection}
      explainer={EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
    >
      {hasFeedbackScores && (
        <div className="flex flex-wrap gap-2 px-6 pb-2 pt-4">
          {data.feedback_scores?.map((score) => (
            <FeedbackScoreTag
              key={score.name}
              label={score.name}
              value={score.value}
              reason={score.reason}
              lastUpdatedAt={score.last_updated_at}
              lastUpdatedBy={score.last_updated_by}
            />
          ))}
        </div>
      )}
      <FeedbackScoresEditor
        feedbackScores={data.feedback_scores || []}
        traceId={traceId}
        spanId={spanId}
        className="mt-4"
      />
    </LastSectionLayout>
  );
};

export default TraceAnnotateViewer;
