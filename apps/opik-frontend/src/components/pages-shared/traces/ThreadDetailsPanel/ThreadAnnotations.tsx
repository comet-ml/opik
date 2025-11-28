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
      scores: [data],
      threadId,
      projectId,
      projectName,
    });
  };

  const onDeleteFeedbackScore = (name: string, author?: string) => {
    threadFeedbackScoreDelete({
      names: [name],
      threadId,
      projectName,
      projectId,
      author,
    });
  };

  return (
    <DetailsActionSectionLayout
      title="Thread scores"
      closeTooltipContent="Close annotate"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
      explainer={EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores]}
    >
      <div className="size-full overflow-y-auto">
        {hasFeedbackScores && (
          <>
            <div className="comet-body-s-accented truncate px-6 pt-4">
              Thread scores
            </div>
            <div className="flex flex-wrap gap-2 px-6 py-2">
              {feedbackScores.map((score) => {
                return (
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
                );
              })}
            </div>
          </>
        )}
        <FeedbackScoresEditor
          key={threadId}
          feedbackScores={feedbackScores}
          onUpdateFeedbackScore={onUpdateFeedbackScore}
          onDeleteFeedbackScore={onDeleteFeedbackScore}
          className="mt-4"
          header={<FeedbackScoresEditor.Header isThread={true} />}
          footer={<FeedbackScoresEditor.Footer entityCopy="threads" />}
        />
      </div>
    </DetailsActionSectionLayout>
  );
};

export default ThreadAnnotations;
