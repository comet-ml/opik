import React from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useTraceCommentsBatchDeleteMutation from "@/api/traces/useTraceCommentsBatchDeleteMutation";
import useCreateTraceCommentMutation from "@/api/traces/useCreateTraceCommentMutation";
import useUpdateTraceCommentMutation from "@/api/traces/useUpdateTraceCommentMutation";
import { orderBy } from "lodash";
import { CommentItems } from "@/types/comment";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import UserComment from "@/components/pages-shared/traces/UserComment/UserComment";
import { ArrayParam, useQueryParam } from "use-query-params";
import { Button } from "@/components/ui/button";
import { Maximize2, MessageSquareMore, Minimize2 } from "lucide-react";

export type ExperimentCommentsViewerCoreProps = {
  comments?: CommentItems;
  traceId: string;
  userName?: string;
  sectionIdx: number;
  experimentId: string;
};

const ExperimentCommentsViewerCore: React.FC<
  ExperimentCommentsViewerCoreProps
> = ({ comments = [], traceId, userName, sectionIdx, experimentId }) => {
  const [expandedCommentSections = [], setExpandedCommentSections] =
    useQueryParam("expandedCommentSections", ArrayParam, {
      updateType: "replaceIn",
    });
  const currentSectionIdx = String(sectionIdx);

  const isExpanded = expandedCommentSections?.includes(currentSectionIdx);
  const toggleIsExpanded = () => {
    const filteredExpandedCommentSections =
      expandedCommentSections?.filter((itm) => itm !== currentSectionIdx) || [];
    const newExpandedCommentSections = isExpanded
      ? filteredExpandedCommentSections
      : [...filteredExpandedCommentSections, currentSectionIdx];

    setExpandedCommentSections(newExpandedCommentSections);
  };

  const traceDeleteMutation = useTraceCommentsBatchDeleteMutation();
  const createTraceMutation = useCreateTraceCommentMutation();
  const updateTraceMutation = useUpdateTraceCommentMutation();

  const onSubmit = (text: string) => {
    createTraceMutation.mutate({
      text,
      traceId,
      experimentId,
    });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    updateTraceMutation.mutate({
      text,
      commentId,
      traceId,
      experimentId,
    });
  };

  const onDelete = (commentId: string) => {
    traceDeleteMutation.mutate({
      ids: [commentId],
      traceId,
      experimentId,
    });
  };

  const expandButtonLabel = comments.length
    ? `Comments (${comments.length})`
    : "Comments";

  return (
    <>
      <Button
        className="my-2 flex w-full justify-between hover:bg-[#F1F5F9] hover:text-foreground active:text-foreground"
        variant="ghost"
        onClick={toggleIsExpanded}
      >
        <div className="flex items-center gap-2 pr-4">
          <MessageSquareMore className="size-4" /> {expandButtonLabel}
        </div>

        {isExpanded ? (
          <Minimize2 className="size-4" />
        ) : (
          <Maximize2 className="size-4" />
        )}
      </Button>
      {isExpanded && (
        <div className="min-h-0 flex-1 overflow-auto">
          <UserCommentForm
            onSubmit={(data) => onSubmit(data.commentText)}
            className="px-3"
            actions={
              <>
                <TooltipWrapper content="Submit" hotkeys={["⌘", "⏎"]}>
                  <UserCommentForm.SubmitButton />
                </TooltipWrapper>
              </>
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
        </div>
      )}
    </>
  );
};

export default ExperimentCommentsViewerCore;
