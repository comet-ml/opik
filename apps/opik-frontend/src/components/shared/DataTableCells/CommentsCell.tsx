import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { CommentItems } from "@/types/comment";
import UserCommentAvatar from "@/components/pages-shared/traces/UserComment/UserCommentAvatar";
import UserCommentHoverList from "@/components/pages-shared/traces/UserComment/UserCommentHoverList";
import UserCommentAvatarList from "@/components/pages-shared/traces/UserComment/UserCommentAvatarList";
import { isLocalCommentCheck } from "@/components/pages-shared/traces/UserComment/UserComment";
import { ExperimentItem, ExperimentsCompare } from "@/types/datasets";
import VerticallySplitCellWrapper, {
  SplitCellRenderContent,
} from "@/components/pages-shared/experiments/VerticallySplitCellWrapper/VerticallySplitCellWrapper";
import { ROW_HEIGHT } from "@/types/shared";
import { cn } from "@/lib/utils";

type CommentsCellContentProps = {
  commentsList: CommentItems;
  isSmall?: boolean;
};
const CommentsCellContent: React.FC<CommentsCellContentProps> = ({
  commentsList,
  isSmall,
}) => {
  const commentsCount = commentsList.length;
  const isLocalComments = isLocalCommentCheck(commentsList[0]?.created_by);

  if (!commentsCount) {
    return "-";
  }

  if (commentsCount === 1) {
    const [comment] = commentsList;
    return (
      <div className="flex items-start gap-1">
        {!isLocalComments && (
          <UserCommentAvatar
            className="mt-0.5"
            username={comment.created_by}
            size="sm"
          />
        )}
        <div
          className={cn(
            "comet-body-s flex-1 min-w-0 text-pretty break-words",
            isSmall && "truncate text-nowrap",
          )}
        >
          {comment.text}
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-start gap-1">
      {!isLocalComments && (
        <UserCommentAvatarList className="mt-0.5" commentsList={commentsList} />
      )}
      <div className="comet-body-s truncate">{commentsCount} comments</div>
    </div>
  );
};

const CompareExperimentsCell: React.FC<
  CellContext<ExperimentsCompare, unknown>
> = (context) => {
  const experimentCompare = context.row.original;
  const { meta: tableMeta } = context.table.options;
  const rowHeight = tableMeta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;

  const onReply = (idx: number) => {
    tableMeta?.onCommentsReply?.(context.row.original, idx);
  };

  const renderContent: SplitCellRenderContent = (
    item: ExperimentItem | undefined,
    _,
    idx,
  ) => {
    const commentsList = item?.comments || [];

    return (
      <UserCommentHoverList
        className={cn(
          "overflow-hidden self-start",
          !isSmall && "overflow-y-auto max-h-full",
        )}
        commentsList={commentsList}
        onReply={() => onReply(idx)}
        showReply={!!tableMeta?.onCommentsReply}
      >
        <CommentsCellContent isSmall={isSmall} commentsList={commentsList} />
      </UserCommentHoverList>
    );
  };

  return (
    <VerticallySplitCellWrapper
      renderContent={renderContent}
      experimentCompare={experimentCompare}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      rowId={context.row.id}
    />
  );
};

const CommentsCell = <TData,>(context: CellContext<TData, unknown>) => {
  const { meta: tableMeta } = context.table.options;
  const rowHeight = context.table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const isSmall = rowHeight === ROW_HEIGHT.small;

  const onReply = () => {
    tableMeta?.onCommentsReply?.(context.row.original);
  };

  const commentsList = (context.getValue() || []) as CommentItems;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="p-0"
    >
      <UserCommentHoverList
        className={cn(
          "px-3 py-2 overflow-hidden",
          !isSmall && "overflow-y-auto max-h-full",
        )}
        commentsList={commentsList}
        onReply={onReply}
        showReply={!!tableMeta?.onCommentsReply}
      >
        <CommentsCellContent commentsList={commentsList} isSmall={isSmall} />
      </UserCommentHoverList>
    </CellWrapper>
  );
};

CommentsCell.Compare = CompareExperimentsCell;

export default CommentsCell;
