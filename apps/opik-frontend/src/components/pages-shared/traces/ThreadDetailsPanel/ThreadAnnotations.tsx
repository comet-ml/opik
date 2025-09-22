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
import useThreadFeedbackScoreSetMutation from "@/api/traces/useThreadFeedbackScoreSetMutation";
import useThreadFeedbackScoreDeleteMutation from "@/api/traces/useThreadFeedbackScoreDeleteMutation";

type ThreadAnnotationsProps = {
  threadId: string;
  projectId: string;
  projectName: string;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
  feedbackScores: TraceFeedbackScore[];
};

const ThreadAnnotations: React.FC<ThreadAnnotationsProps> = ({
  threadId,
  projectId,
  projectName,
  activeSection,
  setActiveSection,
  feedbackScores,
}) => {
  const hasFeedbackScores = Boolean(feedbackScores.length);

  const { mutate: setThreadFeedbackScore } =
    useThreadFeedbackScoreSetMutation();
  const { mutate: threadFeedbackScoreDelete } =
    useThreadFeedbackScoreDeleteMutation();

  const onUpdateFeedbackScore = (data: UpdateFeedbackScoreData) => {
    setThreadFeedbackScore({
      ...data,
      threadId,
      projectId,
      projectName,
    });
  };

  const onDeleteFeedbackScore = (name: string) => {
    threadFeedbackScoreDelete({
      names: [name],
      threadId,
      projectName,
      projectId,
    });
  };

  return (
    <DetailsActionSectionLayout
      title="Feedback scores"
      closeTooltipContent="Close annotate"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
      explainer={EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
    >
      <div className="size-full overflow-y-auto">
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
          entityCopy="threads"
        />
      </div>
    </DetailsActionSectionLayout>
  );
};

export default ThreadAnnotations;
