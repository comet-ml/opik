import React, { useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import uniq from "lodash/uniq";
import find from "lodash/find";

import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import TagCell from "@/components/shared/DataTableCells/TagCell";
import CompareExperimentsHeader from "@/components/pages/CompareExperimentsPage/CompareExperimentsHeader";
import CompareExperimentAddHeader from "@/components/pages/CompareExperimentsPage/CompareExperimentAddHeader";
import Loader from "@/components/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import { Experiment } from "@/types/datasets";
import { FeedbackScoreData } from "@/components/pages/CompareExperimentsPage/helpers";

const COLUMNS_WIDTH_KEY = "compare-experiments-feedback-scores-columns-width";

type FiledValue = string | number | undefined | null;

export const DEFAULT_COLUMNS: ColumnData<FeedbackScoreData>[] = [
  {
    id: "name",
    label: "Feedback score",
    type: COLUMN_TYPE.numberDictionary,
    cell: TagCell as never,
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
    >(DEFAULT_COLUMNS, {
      columnsWidth,
    });

    experimentsIds.forEach((id: string) => {
      const size = columnsWidth[id] ?? 400;
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
        size,
        minSize: 120,
      });
    });

    retVal.push({
      accessorKey: "add_experiment",
      enableHiding: false,
      enableResizing: false,
      size: 48,
      header: CompareExperimentAddHeader as never,
    });

    return retVal;
  }, [columnsWidth, experimentsIds, experiments]);

  const feedbackScoresMap = useMemo(() => {
    return experiments.reduce<Record<string, Record<string, number>>>(
      (acc, e) => {
        acc[e.id] = (e.feedback_scores || [])?.reduce<Record<string, number>>(
          (a, f) => {
            a[f.name] = f.value;
            return a;
          },
          {},
        );

        return acc;
      },
      {},
    );
  }, [experiments]);

  const rows = useMemo(() => {
    const keys = uniq(
      Object.values(feedbackScoresMap).reduce<string[]>(
        (acc, map) => acc.concat(Object.keys(map)),
        [],
      ),
    ).sort();

    return keys.map((key) => {
      const data = experimentsIds.reduce<Record<string, FiledValue>>(
        (acc, id: string) => {
          acc[id] = feedbackScoresMap[id]?.[key] ?? "-";
          return acc;
        },
        {},
      );

      return {
        name: key,
        ...data,
      } as FeedbackScoreData;
    });
  }, [feedbackScoresMap, experimentsIds]);

  const noDataText = isCompare
    ? "These experiments have no feedback scores"
    : "This experiment has no feedback scores";

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pb-6">
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
