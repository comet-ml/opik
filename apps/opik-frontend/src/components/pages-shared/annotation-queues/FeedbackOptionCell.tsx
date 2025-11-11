import { CellContext } from "@tanstack/react-table";
import FeedbackScoreNameCell from "@/components/shared/DataTableCells/FeedbackScoreNameCell";
import FeedbackOptionCommentCell from "./FeedbackOptionCommentCell";

const FeedbackOptionCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  // Route to appropriate cell component based on value
  if (value === "Comments") {
    return FeedbackOptionCommentCell(context);
  }

  return FeedbackScoreNameCell(context);
};

export default FeedbackOptionCell;
