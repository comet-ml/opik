import React, { useMemo } from "react";
import orderBy from "lodash/orderBy";
import { CommentItem } from "@/types/comment";
import { useLoggedInUserName } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import UserCommentForm from "./UserCommentForm";
import UserComment from "./UserComment";

export type CommentsSectionProps = {
  comments: CommentItem[];
  onSubmit: (text: string) => void;
  onEditSubmit: (commentId: string, text: string) => void;
  onDelete: (commentId: string) => void;
  formClassName?: string;
  listClassName?: string;
  commentClassName?: string;
};

const CommentsSection: React.FC<CommentsSectionProps> = ({
  comments,
  onSubmit,
  onEditSubmit,
  onDelete,
  formClassName,
  listClassName,
  commentClassName,
}) => {
  const userName = useLoggedInUserName();
  const {
    permissions: { canWriteComments },
  } = usePermissions();

  const sortedComments = useMemo(
    () => orderBy(comments || [], "created_at", "desc"),
    [comments],
  );

  return (
    <>
      {canWriteComments && (
        <UserCommentForm
          onSubmit={(data) => onSubmit(data.commentText)}
          className={formClassName}
          actions={
            <TooltipWrapper content="Submit" hotkeys={["⌘", "⏎"]}>
              <UserCommentForm.SubmitButton />
            </TooltipWrapper>
          }
        >
          <UserCommentForm.TextareaField placeholder="Add a comment..." />
        </UserCommentForm>
      )}
      <div className={listClassName}>
        {sortedComments.length ? (
          sortedComments.map((comment) => (
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
              className={commentClassName}
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

export default CommentsSection;
