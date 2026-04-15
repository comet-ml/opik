import React, { useCallback } from "react";
import { usePermissions } from "@/contexts/PermissionsContext";
import { TraceFeedbackScore } from "@/types/traces";
import { CommentItem } from "@/types/comment";
import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";
import {
  DetailsActionSectionLayout,
  DetailsActionSectionValue,
} from "@/v2/pages-shared/traces/DetailsActionSection";
import FeedbackScoresEditor from "../FeedbackScoresEditor/FeedbackScoresEditor";
import { UpdateFeedbackScoreData } from "../TraceDetailsPanel/TraceAnnotateViewer/types";
import useThreadFeedbackScoreSetMutation from "@/api/traces/useThreadFeedbackScoreSetMutation";
import useThreadFeedbackScoreDeleteMutation from "@/api/traces/useThreadFeedbackScoreDeleteMutation";
import useCreateThreadCommentMutation from "@/api/traces/useCreateThreadCommentMutation";
import useThreadCommentsBatchDeleteMutation from "@/api/traces/useThreadCommentsBatchDeleteMutation";
import useUpdateThreadCommentMutation from "@/api/traces/useUpdateThreadCommentMutation";
import CommentsSection from "@/shared/UserComment/CommentsSection";
import { Separator } from "@/ui/separator";

type ThreadAnnotatePanelProps = {
  threadId: string;
  projectId: string;
  projectName: string;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
  feedbackScores: TraceFeedbackScore[];
  comments: CommentItem[];
};

const ThreadAnnotatePanel: React.FC<ThreadAnnotatePanelProps> = ({
  threadId,
  projectId,
  projectName,
  activeSection,
  setActiveSection,
  feedbackScores,
  comments,
}) => {
  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

  const hasFeedbackScores = Boolean(feedbackScores.length);

  const { mutate: setThreadFeedbackScore } =
    useThreadFeedbackScoreSetMutation();
  const { mutate: threadFeedbackScoreDelete } =
    useThreadFeedbackScoreDeleteMutation();

  const onUpdateFeedbackScore = useCallback(
    (data: UpdateFeedbackScoreData) => {
      setThreadFeedbackScore({
        scores: [data],
        threadId,
        projectId,
        projectName,
      });
    },
    [setThreadFeedbackScore, threadId, projectId, projectName],
  );

  const onDeleteFeedbackScore = useCallback(
    (name: string, author?: string) => {
      threadFeedbackScoreDelete({
        names: [name],
        threadId,
        projectName,
        projectId,
        author,
      });
    },
    [threadFeedbackScoreDelete, threadId, projectName, projectId],
  );

  const createThreadCommentMutation = useCreateThreadCommentMutation();
  const threadCommentsBatchDeleteMutation =
    useThreadCommentsBatchDeleteMutation();
  const updateThreadCommentMutation = useUpdateThreadCommentMutation();

  const onCommentSubmit = useCallback(
    (text: string) => {
      createThreadCommentMutation.mutate({ text, threadId, projectId });
    },
    [createThreadCommentMutation, threadId, projectId],
  );

  const onCommentEdit = useCallback(
    (commentId: string, text: string) => {
      updateThreadCommentMutation.mutate({ text, commentId, projectId });
    },
    [updateThreadCommentMutation, projectId],
  );

  const onCommentDelete = useCallback(
    (commentId: string) => {
      threadCommentsBatchDeleteMutation.mutate({
        ids: [commentId],
        projectId,
        threadId,
      });
    },
    [threadCommentsBatchDeleteMutation, projectId, threadId],
  );

  return (
    <DetailsActionSectionLayout
      title="Annotate"
      closeTooltipContent="Close annotate"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
    >
      <div className="size-full overflow-y-auto">
        {hasFeedbackScores && (
          <>
            <div className="comet-body-s-accented truncate px-6 pt-4">
              Feedback scores
            </div>
            <div className="flex flex-wrap gap-2 px-6 py-2">
              {feedbackScores.map((score) => (
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
        {canAnnotateTraceSpanThread && (
          <FeedbackScoresEditor
            key={threadId}
            feedbackScores={feedbackScores}
            onUpdateFeedbackScore={onUpdateFeedbackScore}
            onDeleteFeedbackScore={onDeleteFeedbackScore}
            className="mt-4"
            header={<FeedbackScoresEditor.Header isThread={true} />}
            footer={<FeedbackScoresEditor.Footer entityCopy="threads" />}
          />
        )}

        <Separator className="mx-6 my-4 w-auto" />

        <div className="comet-body-s-accented truncate px-6">Comments</div>
        <CommentsSection
          comments={comments}
          onSubmit={onCommentSubmit}
          onEditSubmit={onCommentEdit}
          onDelete={onCommentDelete}
          formClassName="mt-2 px-6"
          listClassName="mt-3 h-full overflow-auto pb-3"
          commentClassName="px-6 hover:bg-soft-background"
        />
      </div>
    </DetailsActionSectionLayout>
  );
};

export default ThreadAnnotatePanel;
