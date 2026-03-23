import React, { useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import find from "lodash/find";
import uniq from "lodash/uniq";

import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import TextCell from "@/shared/DataTableCells/TextCell";
import AssertionPassRateCell from "@/v1/pages/CompareExperimentsPage/ExperimentAssertionsTab/AssertionPassRateCell";
import CompareExperimentsHeader from "@/v1/pages-shared/experiments/CompareExperimentsHeader/CompareExperimentsHeader";
import CompareExperimentsActionsPanel from "@/v1/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/v1/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import Loader from "@/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import { AssertionScoreAverage, Experiment } from "@/types/datasets";

type AssertionRowData = {
  name: string;
} & Record<string, AssertionScoreAverage | string | undefined>;

type AssertionScoreMap = Record<string, Record<string, AssertionScoreAverage>>;

function getAssertionScoreMap(experiments: Experiment[]): AssertionScoreMap {
  return experiments.reduce<AssertionScoreMap>((acc, e) => {
    acc[e.id] = (e.assertion_scores ?? []).reduce<
      Record<string, AssertionScoreAverage>
    >((a, score) => {
      a[score.name] = score;
      return a;
    }, {});
    return acc;
  }, {});
}

interface GetAssertionRowsArguments {
  scoreMap: AssertionScoreMap;
  experimentsIds: string[];
}

function getAssertionRowsForExperiments({
  scoreMap,
  experimentsIds,
}: GetAssertionRowsArguments): AssertionRowData[] {
  const names = uniq(
    Object.values(scoreMap).reduce<string[]>(
      (acc, map) => acc.concat(Object.keys(map)),
      [],
    ),
  ).sort();

  return names.map((name) => {
    const data = experimentsIds.reduce<
      Record<string, AssertionScoreAverage | undefined>
    >((acc, id) => {
      acc[id] = scoreMap[id]?.[name] ?? undefined;
      return acc;
    }, {});

    return {
      name,
      ...data,
    };
  });
}

const COLUMNS_WIDTH_KEY = "compare-experiments-assertions-columns-width";

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: ["name"],
  right: [],
};

const DEFAULT_COLUMNS: ColumnData<AssertionRowData>[] = [
  {
    id: "name",
    label: "Assertion",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
    size: 248,
  },
];

type ExperimentAssertionsTabProps = {
  experimentsIds: string[];
  experiments: Experiment[];
  isPending: boolean;
};

const ExperimentAssertionsTab: React.FunctionComponent<
  ExperimentAssertionsTabProps
> = ({ experimentsIds, experiments, isPending }) => {
  const isCompare = experimentsIds.length > 1;

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      AssertionRowData,
      AssertionRowData
    >(DEFAULT_COLUMNS, {});

    experimentsIds.forEach((id) => {
      retVal.push({
        accessorKey: id,
        header: CompareExperimentsHeader as never,
        cell: AssertionPassRateCell as never,
        meta: {
          type: COLUMN_TYPE.string,
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

  const scoreMap = useMemo(
    () => getAssertionScoreMap(experiments),
    [experiments],
  );

  const rows = useMemo(
    () => getAssertionRowsForExperiments({ scoreMap, experimentsIds }),
    [scoreMap, experimentsIds],
  );

  const noDataText = isCompare
    ? "These experiments have no assertions"
    : "This experiment has no assertions";

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
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex items-center justify-end gap-8 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <CompareExperimentsActionsPanel />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        resizeConfig={resizeConfig}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        stickyHeader
      />
      <PageBodyStickyContainer
        className="pb-6"
        direction="horizontal"
        limitWidth
      />
    </>
  );
};

export default ExperimentAssertionsTab;
