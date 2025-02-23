import React from "react";
import { Span, Trace } from "@/types/traces";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import UserCommentForm from "../../UserComment/UserCommentForm";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useSpanCommentsBatchDeleteMutation from "@/api/traces/useSpanCommentsBatchDeleteMutation";
import useCreateSpanCommentMutation from "@/api/traces/useCreateSpanCommentMutation";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useUpdateSpanCommentMutation from "@/api/traces/useUpdateSpanCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import { LastSectionValue } from "../TraceDetailsPanel";
import UserComment from "../../UserComment/UserComment";
import LastSectionLayout from "../LastSectionLayout";
import { orderBy } from "lodash";

export type CommentsViewerCoreProps = {
  data: Trace | Span;
  spanId?: string;
  traceId: string;
  projectId: string;
  lastSection?: LastSectionValue | null;
  setLastSection: (v: LastSectionValue | null) => void;
  userName?: string;
};

const CommentsViewerCore: React.FC<CommentsViewerCoreProps> = ({
  data,
  spanId,
  traceId,
  lastSection,
  setLastSection,
  projectId,
  userName,
}) => {
  const traceDeleteMutation = useTraceCommentsBatchDeleteMutation();
  const spanDeleteMutation = useSpanCommentsBatchDeleteMutation();

  const createSpanMutation = useCreateSpanCommentMutation();
  const createTraceMutation = useCreateTraceCommentMutation();

  const updateSpanMutation = useUpdateSpanCommentMutation();
  const updateTraceMutation = useUpdateTraceCommentMutation();

  const onSubmit = (text: string) => {
    if (!spanId) {
      createTraceMutation.mutate({
        text,
        traceId,
      });
      return;
    }

    createSpanMutation.mutate({
      text,
      spanId,
      projectId,
    });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    if (!spanId) {
      updateTraceMutation.mutate({
        text,
        commentId,
        traceId,
      });
      return;
    }

    updateSpanMutation.mutate({
      text,
      commentId,
      projectId,
    });
  };

  const onDelete = (commentId: string) => {
    if (!spanId) {
      traceDeleteMutation.mutate({
        ids: [commentId],
        traceId,
      });
      return;
    }

    spanDeleteMutation.mutate({
      ids: [commentId],
      projectId,
    });
  };

  return (
    <LastSectionLayout
      title="Comments"
      closeTooltipContent="Close comments"
      setLastSection={setLastSection}
      lastSection={lastSection}
    >
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
        {data.comments?.length ? (
          orderBy(data.comments, "created_at", "desc").map((comment) => (
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
    </LastSectionLayout>
  );
};

export default CommentsViewerCore;
