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
  const title = isTrace ? "Trace feedback scores" : "Span feedback scores";
  const scoresSectionTitle = isTrace ? "Trace scores" : "Span scores";

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

  // Only show feedback scores for the current entity type
  // When showing a trace, only show trace feedback scores (not span scores)
  // When showing a span, only show span feedback scores (not trace scores)
  const allFeedbackScores = useMemo(
    () => data.feedback_scores || [],
    [data.feedback_scores],
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
        {hasFeedbackScores && (
          <>
            <div className="comet-body-s-accented truncate px-6 pt-4">
              {scoresSectionTitle}
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
          header={<FeedbackScoresEditor.Header isTrace={isTrace} />}
          footer={<FeedbackScoresEditor.Footer entityCopy={isTrace ? "traces" : "spans"} />}
          isSpanFeedbackScores={isTrace}
        />
      </div>
    </DetailsActionSectionLayout>
  );
};

export default TraceAnnotateViewer;
