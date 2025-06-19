import React from "react";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import FeedbackScoresEditor from "../FeedbackScoresEditor/FeedbackScoresEditor";
import { UpdateFeedbackScoreData } from "../TraceDetailsPanel/TraceAnnotateViewer/types";

type ThreadAnnotationsProps = {
  threadId: string;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
};

const ThreadAnnotations: React.FC<ThreadAnnotationsProps> = ({
  threadId,
  activeSection,
  setActiveSection,
}) => {
  const feedbackScores: TraceFeedbackScore[] = [];
  const hasFeedbackScores = Boolean(feedbackScores.length);

  const onUpdateFeedbackScore = (data: UpdateFeedbackScoreData) => {
    console.log("onUpdateFeedbackScore", data);
  };

  const onDeleteFeedbackScore = (name: string) => {
    console.log("onDeleteFeedbackScore", name);
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
          {feedbackScores.map((score) => (
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
        key={threadId}
        feedbackScores={feedbackScores}
        onUpdateFeedbackScore={onUpdateFeedbackScore}
        onDeleteFeedbackScore={onDeleteFeedbackScore}
        className="mt-4"
      />
    </DetailsActionSectionLayout>
  );
};

export default ThreadAnnotations;
