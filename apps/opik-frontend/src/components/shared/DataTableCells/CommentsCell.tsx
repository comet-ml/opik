import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Comment } from "@/types/comment";
import UserCommentAvatar from "@/components/pages-shared/traces/UserComment/UserCommentAvatar";
import { LastSectionValue } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import UserCommentHoverList from "@/components/pages-shared/traces/UserComment/UserCommentHoverList";
import UserCommentAvatarList from "@/components/pages-shared/traces/UserComment/UserCommentAvatarList";
import { isLocalCommentCheck } from "@/components/pages-shared/traces/UserComment/UserComment";

type CommentsCellContentProps = {
  commentsList: Comment[];
};
const CommentsCellContent: React.FC<CommentsCellContentProps> = ({
  commentsList,
}) => {
  const commentsCount = commentsList.length;
  const isLocalComments = isLocalCommentCheck(commentsList[0]?.created_by);

  if (!commentsCount) {
    return "-";
  }

  if (commentsCount === 1) {
    const [comment] = commentsList;
    return (
      <div className="flex items-center gap-1 overflow-hidden">
        {!isLocalComments && (
          <UserCommentAvatar username={comment.created_by} size="sm" />
        )}
        <div className="comet-body-s truncate">{comment.text}</div>
      </div>
    );
  }

  return (
    <div className="flex items-center gap-1 overflow-hidden">
      {!isLocalComments && (
        <UserCommentAvatarList commentsList={commentsList} />
      )}
      <div className="comet-body-s truncate">{commentsCount} comments</div>
    </div>
  );
};

type CustomMeta<TData> = {
  callback: (row: TData, lastSection: LastSectionValue) => void;
  asId: boolean;
};

// TODO add compare CELL
const CommentsCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { callback } = (custom ?? {}) as CustomMeta<TData>;
  const commentsList = (context.getValue() || []) as Comment[];

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="py-1"
    >
      <UserCommentHoverList
        commentsList={commentsList}
        onReply={() => callback(context.row.original, "comments")}
      >
        <CommentsCellContent commentsList={commentsList} />
      </UserCommentHoverList>
    </CellWrapper>
  );
};

export default CommentsCell;
