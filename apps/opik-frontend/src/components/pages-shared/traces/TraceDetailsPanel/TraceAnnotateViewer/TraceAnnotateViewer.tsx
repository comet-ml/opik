import React from "react";
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

  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();
  const { mutate: feedbackScoreDelete } = useTraceFeedbackScoreDeleteMutation();

  const onUpdateFeedbackScore = (data: UpdateFeedbackScoreData) => {
    setTraceFeedbackScore({
      ...data,
      traceId,
      spanId,
    });
  };

  const onDeleteFeedbackScore = (name: string) => {
    feedbackScoreDelete({ name, traceId, spanId });
  };

  return (
    <DetailsActionSectionLayout
      title="Feedback scores"
      closeTooltipContent="Close annotate"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
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
        key={traceId ?? spanId}
        feedbackScores={data.feedback_scores || []}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
        className="mt-4"
        entityCopy="traces"
      />
    </DetailsActionSectionLayout>
  );
};

export default TraceAnnotateViewer;
