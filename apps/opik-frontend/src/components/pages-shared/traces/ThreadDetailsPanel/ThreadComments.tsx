import React from "react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import UserCommentForm from "@/components/pages-shared/traces/UserComment/UserCommentForm";
import UserComment from "@/components/pages-shared/traces/UserComment/UserComment";
import { orderBy } from "lodash";
import { useLoggedInUserName } from "@/store/AppStore";
import {
  DetailsActionSectionValue,
  DetailsActionSectionLayout,
} from "@/components/pages-shared/traces/DetailsActionSection";
import { CommentItem } from "@/types/comment";

export type ThreadCommentsProps = {
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
};

const ThreadComments: React.FC<ThreadCommentsProps> = ({
  activeSection,
  setActiveSection,
}) => {
  //   const traceDeleteMutation = useTraceCommentsBatchDeleteMutation();
  //   const spanDeleteMutation = useSpanCommentsBatchDeleteMutation();

  //   const createSpanMutation = useCreateSpanCommentMutation();
  //   const createTraceMutation = useCreateTraceCommentMutation();

  //   const updateSpanMutation = useUpdateSpanCommentMutation();
  //   const updateTraceMutation = useUpdateTraceCommentMutation();

  const comments: CommentItem[] = [];

  const userName = useLoggedInUserName();

  const onSubmit = (text: string) => {
    console.log("submit", text);
    // if (!spanId) {
    //   createTraceMutation.mutate({
    //     text,
    //     traceId,
    //   });
    //   return;
    // }

    // createSpanMutation.mutate({
    //   text,
    //   spanId,
    //   projectId,
    // });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    console.log("edit submit", text, commentId);
    // if (!spanId) {
    //   updateTraceMutation.mutate({
    //     text,
    //     commentId,
    //     traceId,
    //   });
    //   return;
    // }

    // updateSpanMutation.mutate({
    //   text,
    //   commentId,
    //   projectId,
    // });
  };

  const onDelete = (commentId: string) => {
    console.log("delete", commentId);
    // if (!spanId) {
    //   traceDeleteMutation.mutate({
    //     ids: [commentId],
    //     traceId,
    //   });
    //   return;
    // }

    // spanDeleteMutation.mutate({
    //   ids: [commentId],
    //   projectId,
    // });
  };

  return (
    <DetailsActionSectionLayout
      title="Comments"
      closeTooltipContent="Close comments"
      setActiveSection={setActiveSection}
      activeSection={activeSection}
    >
      <UserCommentForm
        onSubmit={(data) => onSubmit(data.commentText)}
        className="mt-4 px-6"
        actions={
          <TooltipWrapper content={"Submit"} hotkeys={["⌘", "⏎"]}>
            <UserCommentForm.SubmitButton />
          </TooltipWrapper>
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
    </DetailsActionSectionLayout>
  );
};

export default ThreadComments;
