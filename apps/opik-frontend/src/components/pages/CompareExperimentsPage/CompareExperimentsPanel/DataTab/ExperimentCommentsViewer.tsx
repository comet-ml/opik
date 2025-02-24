import React from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import { orderBy } from "lodash";
import { Comments } from "@/types/comment";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import UserComment from "@/components/pages-shared/traces/UserComment/UserComment";

export type CommentsViewerCoreProps = {
  comments?: Comments;
  traceId: string;
  userName?: string;
};

const ExperimentCommentsViewerCore: React.FC<CommentsViewerCoreProps> = ({
  comments = [],
  traceId,
  userName,
}) => {
  const traceDeleteMutation = useTraceCommentsBatchDeleteMutation();
  const createTraceMutation = useCreateTraceCommentMutation();
  const updateTraceMutation = useUpdateTraceCommentMutation();

  const onSubmit = (text: string) => {
    createTraceMutation.mutate({
      text,
      traceId,
    });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    updateTraceMutation.mutate({
      text,
      commentId,
      traceId,
    });
  };

  const onDelete = (commentId: string) => {
    traceDeleteMutation.mutate({
      ids: [commentId],
      traceId,
    });
  };

  return (
    <>
      <UserCommentForm
        onSubmit={(data) => onSubmit(data.commentText)}
        className="mt-4 px-6"
        actions={
          <>
            <TooltipWrapper content={"Submit"} hotkeys={["⌘", "⏎"]}>
              <UserCommentForm.SubmitButton />
            </TooltipWrapper>
          </>
        }
      >
        <UserCommentForm.TextareaField placeholder="Add a comment..." />
      </UserCommentForm>
      <div className="mt-3 h-full overflow-auto pb-3">
        {comments?.length ? (
          orderBy(comments, "created_at", "desc").map((comment) => (
            <UserComment
              key={comment.id}
              comment={comment}
              avatar={<UserComment.Avatar />}
              actions={
                <UserComment.Menu>
                  <UserComment.MenuEditItem />
                  <UserComment.MenuDeleteItem onDelete={onDelete} />
                </UserComment.Menu>
              }
              userName={userName}
              header={
                <>
                  <UserComment.Username />
                  <UserComment.CreatedAt />
                </>
              }
              className="px-6 hover:bg-soft-background"
            >
              <UserComment.Text />
              <UserComment.Form onSubmit={onEditSubmit} />
            </UserComment>
          ))
        ) : (
          <div className="comet-body-s py-3 text-center text-muted-slate">
            No comments yet
          </div>
        )}
      </div>
    </>
  );
};

export default ExperimentCommentsViewerCore;
