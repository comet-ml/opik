import { CellContext } from "@tanstack/react-table";
import ScoreListCell from "./ScoreListCell";
import { SCORE_TYPE_FEEDBACK } from "@/types/shared";

const FeedbackScoreListCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  return <ScoreListCell context={context} scoreType={SCORE_TYPE_FEEDBACK} />;
};

const FeedbackScoreListAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  return (
    <ScoreListCell.Aggregation
      context={context}
      scoreType={SCORE_TYPE_FEEDBACK}
    />
  );
};

FeedbackScoreListCell.Aggregation = FeedbackScoreListAggregationCell;

export default FeedbackScoreListCell;
