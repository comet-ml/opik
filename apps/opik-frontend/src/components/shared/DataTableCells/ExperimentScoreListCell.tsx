import { CellContext } from "@tanstack/react-table";
import ScoreListCell from "./ScoreListCell";
import { SCORE_TYPE_EXPERIMENT } from "@/types/shared";

const ExperimentScoreListCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  return <ScoreListCell context={context} scoreType={SCORE_TYPE_EXPERIMENT} />;
};

const ExperimentScoreListAggregationCell = <TData,>(
  context: CellContext<TData, string>,
) => {
  return (
    <ScoreListCell.Aggregation
      context={context}
      scoreType={SCORE_TYPE_EXPERIMENT}
    />
  );
};

ExperimentScoreListCell.Aggregation = ExperimentScoreListAggregationCell;

export default ExperimentScoreListCell;
