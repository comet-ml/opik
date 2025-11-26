import React, { useMemo } from "react";
import { Span, Trace } from "@/types/traces";
import FeedbackScoresEditor from "../../FeedbackScoresEditor/FeedbackScoresEditor";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import { UpdateFeedbackScoreData } from "./types";

type TraceAnnotateViewerProps = {
  data: Trace | Span;
  spanId?: string;
  traceId: string;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
};

const TraceAnnotateViewer: React.FunctionComponent<
  TraceAnnotateViewerProps
> = ({ data, spanId, traceId, activeSection, setActiveSection }) => {
  const hasFeedbackScores = Boolean(data.feedback_scores?.length);
  const isTrace = !spanId;
  const traceData = isTrace ? (data as Trace) : undefined;
  const hasSpanFeedbackScores = Boolean(
    traceData?.span_feedback_scores?.length,
  );
  const title = isTrace ? "Trace scores" : "Span scores";

  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();
  const { mutate: feedbackScoreDelete } = useTraceFeedbackScoreDeleteMutation();

  const onUpdateFeedbackScore = (data: UpdateFeedbackScoreData) => {
    setTraceFeedbackScore({
      ...data,
      traceId,
      spanId,
    });
  };

  const onDeleteFeedbackScore = (
    name: string,
    author?: string,
    spanIdToDelete?: string,
  ) => {
    // If spanIdToDelete is provided (for span feedback scores), use it
    // Otherwise use the current spanId (for trace/span context)
    const targetSpanId = spanIdToDelete ?? spanId;
    feedbackScoreDelete({ name, traceId, spanId: targetSpanId, author });
  };

  // Combine trace and span feedback scores for display
  const allFeedbackScores = useMemo(
    () => [
      ...(data.feedback_scores || []),
      ...(isTrace && traceData?.span_feedback_scores
        ? traceData.span_feedback_scores
        : []),
    ],
    [data.feedback_scores, isTrace, traceData?.span_feedback_scores],
  );

  return (
    <DetailsActionSectionLayout
      title={title}
      closeTooltipContent="Close annotate"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
      explainer={EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
    >
      <div className="size-full overflow-y-auto">
        {(hasFeedbackScores || hasSpanFeedbackScores) && (
          <>
            <div className="comet-body-s-accented truncate px-6 pt-4">
              All scores
            </div>
            <div className="flex flex-wrap gap-2 px-6 py-2">
              {allFeedbackScores.map((score) => (
                <FeedbackScoreTag
                  key={score.name}
                  label={score.name}
                  value={score.value}
                  reason={score.reason}
                  lastUpdatedAt={score.last_updated_at}
                  lastUpdatedBy={score.last_updated_by}
                  valueByAuthor={score.value_by_author}
                  category={score.category_name}
                />
              ))}
            </div>
          </>
        )}
        <FeedbackScoresEditor
          key={`${traceId}-${spanId}`}
          feedbackScores={allFeedbackScores}
          onUpdateFeedbackScore={onUpdateFeedbackScore}
          onDeleteFeedbackScore={onDeleteFeedbackScore}
          className="mt-4"
          header={<FeedbackScoresEditor.Header />}
          footer={<FeedbackScoresEditor.Footer entityCopy="traces" />}
          isSpanFeedbackScores={isTrace}
        />
      </div>
    </DetailsActionSectionLayout>
  );
};

export default TraceAnnotateViewer;
