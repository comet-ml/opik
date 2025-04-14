import React from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import { orderBy } from "lodash";
import { CommentItems } from "@/types/comment";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import UserComment from "@/components/pages-shared/traces/UserComment/UserComment";
import { MessageSquareMore } from "lucide-react";
import { useLoggedInUserName } from "@/store/AppStore";
import ExpandableSection from "@/components/shared/ExpandableSection/ExpandableSection";

export type ExperimentCommentsViewerProps = {
  comments?: CommentItems;
  traceId: string;
  sectionIdx: number;
};

const ExperimentCommentsViewer: React.FC<ExperimentCommentsViewerProps> = ({
  comments = [],
  traceId,
  sectionIdx,
}) => {
  const userName = useLoggedInUserName();

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
    <ExpandableSection
      icon={<MessageSquareMore className="size-4" />}
      title="Comments"
      queryParamName="expandedCommentSections"
      sectionIdx={sectionIdx}
      count={comments.length}
    >
      <UserCommentForm
        onSubmit={(data) => onSubmit(data.commentText)}
        className="px-3"
        actions={
          <TooltipWrapper content="Submit" hotkeys={["⌘", "⏎"]}>
            <UserCommentForm.SubmitButton />
          </TooltipWrapper>
        }
      >
        <UserCommentForm.TextareaField placeholder="Add a comment..." />
      </UserCommentForm>
      <div className="mt-2 pb-3">
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
              className="px-3 py-2 hover:bg-soft-background"
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
    </ExpandableSection>
  );
};

export default ExperimentCommentsViewer;
