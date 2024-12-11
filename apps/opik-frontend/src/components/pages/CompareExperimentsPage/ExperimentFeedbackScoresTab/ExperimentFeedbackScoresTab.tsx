import React, { useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import find from "lodash/find";

import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import FeedbackScoreNameCell from "@/components/shared/DataTableCells/FeedbackScoreNameCell";
import CompareExperimentsHeader from "@/components/pages/CompareExperimentsPage/CompareExperimentsHeader";
import CompareExperimentsActionsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import Loader from "@/components/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import { Experiment } from "@/types/datasets";
import {
  FeedbackScoreData,
  getFeedbackScoreMap,
  getFeedbackScoresForExperimentsAsRows,
} from "@/components/pages/CompareExperimentsPage/helpers";

const COLUMNS_WIDTH_KEY = "compare-experiments-feedback-scores-columns-width";

export const DEFAULT_COLUMNS: ColumnData<FeedbackScoreData>[] = [
  {
    id: "name",
    label: "Feedback score",
    type: COLUMN_TYPE.numberDictionary,
    cell: FeedbackScoreNameCell as never,
    size: 248,
  },
];

export type ExperimentFeedbackScoresTabProps = {
  experimentsIds: string[];
  experiments: Experiment[];
  isPending: boolean;
};
const ExperimentFeedbackScoresTab: React.FunctionComponent<
  ExperimentFeedbackScoresTabProps
> = ({ experimentsIds, experiments, isPending }) => {
  const isCompare = experimentsIds.length > 1;

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      FeedbackScoreData,
      FeedbackScoreData
    >(DEFAULT_COLUMNS, {});

    experimentsIds.forEach((id: string) => {
      retVal.push({
        accessorKey: id,
        header: CompareExperimentsHeader as never,
        cell: TextCell as never,
        meta: {
          type: COLUMN_TYPE.number,
          custom: {
            experiment: find(experiments, (e) => e.id === id),
          },
        },
        size: 400,
        minSize: 120,
      });
    });

    return retVal;
  }, [experimentsIds, experiments]);

  const feedbackScoresMap = useMemo(() => {
    return getFeedbackScoreMap({
      experiments,
    });
  }, [experiments]);

  const rows = useMemo(() => {
    return getFeedbackScoresForExperimentsAsRows({
      feedbackScoresMap,
      experimentsIds,
    });
  }, [feedbackScoresMap, experimentsIds]);

  const noDataText = isCompare
    ? "These experiments have no feedback scores"
    : "This experiment has no feedback scores";

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pb-6">
      <div className="mb-6 flex items-center justify-end gap-8">
        <div className="flex items-center gap-2">
          <CompareExperimentsActionsPanel />
        </div>
      </div>
      <DataTable
        columns={columns}
        data={rows}
        resizeConfig={resizeConfig}
        noData={<DataTableNoData title={noDataText} />}
      />
    </div>
  );
};

export default ExperimentFeedbackScoresTab;
