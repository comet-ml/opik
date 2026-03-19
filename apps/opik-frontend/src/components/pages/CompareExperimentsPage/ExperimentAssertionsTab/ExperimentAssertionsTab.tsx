import React, { useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import find from "lodash/find";
import uniq from "lodash/uniq";

import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import AssertionPassRateCell from "@/components/pages/CompareExperimentsPage/ExperimentAssertionsTab/AssertionPassRateCell";
import CompareExperimentsHeader from "@/components/pages-shared/experiments/CompareExperimentsHeader/CompareExperimentsHeader";
import CompareExperimentsActionsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import Loader from "@/components/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import { AssertionAggregation, Experiment } from "@/types/datasets";

type AssertionRowData = {
  name: string;
} & Record<string, AssertionAggregation | string | undefined>;

type AssertionAggregationMap = Record<
  string,
  Record<string, AssertionAggregation>
>;

function getAssertionAggregationMap(
  experiments: Experiment[],
): AssertionAggregationMap {
  return experiments.reduce<AssertionAggregationMap>((acc, e) => {
    acc[e.id] = (e.assertion_aggregations ?? []).reduce<
      Record<string, AssertionAggregation>
    >((a, agg) => {
      a[agg.name] = agg;
      return a;
    }, {});
    return acc;
  }, {});
}

interface GetAssertionRowsArguments {
  aggregationMap: AssertionAggregationMap;
  experimentsIds: string[];
}

function getAssertionRowsForExperiments({
  aggregationMap,
  experimentsIds,
}: GetAssertionRowsArguments): AssertionRowData[] {
  const names = uniq(
    Object.values(aggregationMap).reduce<string[]>(
      (acc, map) => acc.concat(Object.keys(map)),
      [],
    ),
  ).sort();

  return names.map((name) => {
    const data = experimentsIds.reduce<
      Record<string, AssertionAggregation | undefined>
    >((acc, id) => {
      acc[id] = aggregationMap[id]?.[name] ?? undefined;
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

  const aggregationMap = useMemo(
    () => getAssertionAggregationMap(experiments),
    [experiments],
  );

  const rows = useMemo(
    () => getAssertionRowsForExperiments({ aggregationMap, experimentsIds }),
    [aggregationMap, experimentsIds],
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
