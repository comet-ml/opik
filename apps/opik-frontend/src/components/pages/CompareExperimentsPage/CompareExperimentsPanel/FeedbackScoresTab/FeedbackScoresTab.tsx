// import React from "react";

import { ExperimentItem } from "@/types/datasets";
import React, { useMemo } from "react";
import {
  FeedbackScoreData,
  getFeedbackScoreMap,
  getFeedbackScoresForExperimentsAsRows,
} from "@/components/pages/CompareExperimentsPage/helpers";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTable from "@/components/shared/DataTable/DataTable";
import { convertColumnDataToColumn } from "@/lib/table";

import { COLUMN_TYPE } from "@/types/shared";
import ExperimentHeader from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/FeedbackScoresTab/ExperimentHeader";
import FeedbackScoreCell from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/FeedbackScoresTab/FeedbackScoreCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";

interface FeedbackScoresTabProps {
  experimentItems: ExperimentItem[];
}

const FeedbackScoresTab = ({ experimentItems }: FeedbackScoresTabProps) => {
  // ALEX
  // maybe use a hook instead

  const noDataText =
    experimentItems?.length > 0
      ? "These experiments have no feedback scores"
      : "This experiment has no feedback scores";

  const feedbackScoresMap = useMemo(() => {
    return getFeedbackScoreMap({
      experiments: experimentItems.map((experimentItem) => ({
        id: experimentItem.experiment_id,
        feedback_scores: experimentItem.feedback_scores || [],
      })),
    });
  }, [experimentItems]);

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      FeedbackScoreData,
      FeedbackScoreData
    >(
      [
        {
          id: "name",
          label: "Feedback score",
          type: COLUMN_TYPE.numberDictionary,
          cell: FeedbackScoreCell as never,
        },
      ],
      {},
    );

    experimentItems.forEach((experimentItem) => {
      retVal.push({
        accessorKey: experimentItem.experiment_id,
        header: ExperimentHeader as never,
        cell: TextCell as never,
        meta: {
          type: COLUMN_TYPE.number,
          custom: {
            experimentId: experimentItem.experiment_id,
          },
        },
      });
    });

    return retVal;
  }, [experimentItems]);

  const rows = useMemo(() => {
    return getFeedbackScoresForExperimentsAsRows({
      feedbackScoresMap,
      experimentsIds: experimentItems.map(
        (item: ExperimentItem) => item.experiment_id,
      ),
    });
  }, [feedbackScoresMap, experimentItems]);

  return (
    <div className="pb-8">
      <DataTable
        columns={columns}
        data={rows}
        noData={<DataTableNoData title={noDataText} />}
      />
    </div>
  );
};

export default FeedbackScoresTab;
