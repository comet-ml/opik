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
import useCreateThreadCommentMutation from "@/api/traces/useCreateThreadCommentMutation";
import useThreadCommentsBatchDeleteMutation from "@/api/traces/useThreadCommentsBatchDeleteMutation";
import useUpdateThreadCommentMutation from "@/api/traces/useUpdateThreadCommentMutation";
import { usePermissions } from "@/contexts/PermissionsContext";

export type ThreadCommentsProps = {
  activeSection?: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue | null) => void;
  comments: CommentItem[];
  threadId: string;
  projectId: string;
};

const ThreadComments: React.FC<ThreadCommentsProps> = ({
  activeSection,
  setActiveSection,
  comments,
  threadId,
  projectId,
}) => {
  const threadCommentsBatchDeleteMutation =
    useThreadCommentsBatchDeleteMutation();
  const createThreadCommentMutation = useCreateThreadCommentMutation();
  const updateThreadCommentMutation = useUpdateThreadCommentMutation();

  const userName = useLoggedInUserName();
  const { permissions: { canInteractWithApp } } = usePermissions();

  const onSubmit = (text: string) => {
    createThreadCommentMutation.mutate({
      text,
      threadId,
      projectId,
    });
  };

  const onEditSubmit = (commentId: string, text: string) => {
    updateThreadCommentMutation.mutate({
      text,
      commentId,
      projectId,
    });
  };

  const onDelete = (commentId: string) => {
    threadCommentsBatchDeleteMutation.mutate({
      ids: [commentId],
      projectId,
      threadId,
    });
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
            <UserCommentForm.SubmitButton disabled={!canInteractWithApp} />
          </TooltipWrapper>
        }
      >
        <UserCommentForm.TextareaField placeholder="Add a comment..." disabled={!canInteractWithApp} />
      </UserCommentForm>
      <div className="mt-3 h-full overflow-auto pb-3">
        {comments?.length ? (
          orderBy(comments, "created_at", "desc").map((comment) => (
            <UserComment
              key={comment.id}
              comment={comment}
              avatar={<UserComment.Avatar />}
              actions={
                canInteractWithApp ? (
                  <UserComment.Menu>
                    <UserComment.MenuEditItem />
                    <UserComment.MenuDeleteItem onDelete={onDelete} />
                  </UserComment.Menu>
                ) : undefined
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
              {canInteractWithApp && <UserComment.Form onSubmit={onEditSubmit} />}
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
