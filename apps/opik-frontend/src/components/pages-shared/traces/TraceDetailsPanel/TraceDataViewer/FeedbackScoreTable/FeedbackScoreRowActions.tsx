import React from "react";
import { ExpandingFeedbackScoreRow } from "./types";
import { getIsParentFeedbackScoreRow } from "./utils";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { useLoggedInUserName } from "@/store/AppStore";
import { RowActionsButtons } from "@/components/shared/DataTable/RowActionsButtons";

type FeedbackScoreRowActionsProps = {
  row: ExpandingFeedbackScoreRow;
  onDelete: (row: ExpandingFeedbackScoreRow) => void;
};

const FeedbackScoreRowActions: React.FC<FeedbackScoreRowActionsProps> = ({
  row,
  onDelete,
}) => {
  const currentUserName = useLoggedInUserName();

  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(row);
  const isUserOwner = row.created_by === currentUserName;
  const isOnlineEvaluationScore =
    row.source === FEEDBACK_SCORE_TYPE.online_scoring;

  if (isParentFeedbackScoreRow || (isOnlineEvaluationScore && !isUserOwner)) {
    return null;
  }

  return (
    <RowActionsButtons
      actions={[{ type: "delete", onClick: () => onDelete(row) }]}
    />
  );
};

export default FeedbackScoreRowActions;
