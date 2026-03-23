import { useMemo } from "react";
import { ColumnSort } from "@tanstack/react-table";
import { keepPreviousData } from "@tanstack/react-query";

import {
  COLUMN_COMMENTS_ID,
  COLUMN_DURATION_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_TYPE,
  COLUMN_USAGE_ID,
  DynamicColumn,
  OnChangeFn,
  SCORE_TYPE_EXPERIMENT,
} from "@/types/shared";
import { Filter } from "@/types/filters";
import {
  EXPERIMENT_ITEM_OUTPUT_PREFIX,
  EXPERIMENT_ITEM_DATASET_PREFIX,
} from "@/constants/experiments";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useCompareExperimentsColumns from "@/api/datasets/useCompareExperimentsColumns";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import useExperimentItemsStatistic from "@/api/datasets/useExperimentItemsStatistic";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";

const REFETCH_INTERVAL = 30000;

type UseExperimentItemsDataParams = {
  workspaceName: string;
  datasetId: string;
  experimentsIds: string[];
  filters: Filter[];
  sorting: ColumnSort[];
  search: string;
  page: number;
  size: number;
  truncate: boolean;
  setSelectedColumns: OnChangeFn<string[]>;
  dynamicColumnsKey: string;
};

const useExperimentItemsData = ({
  workspaceName,
  datasetId,
  experimentsIds,
  filters,
  sorting,
  search,
  page,
  size,
  truncate,
  setSelectedColumns,
  dynamicColumnsKey,
}: UseExperimentItemsDataParams) => {
  const { data, isPending, isPlaceholderData, isFetching } =
    useCompareExperimentsList(
      {
        workspaceName,
        datasetId,
        experimentsIds,
        filters,
        sorting,
        search,
        truncate,
        page,
        size,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const { refetch: refetchExportData } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds,
      filters,
      truncate,
      page,
      size,
    },
    {
      enabled: false,
    },
  );

  const sortableColumns = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const { data: experimentsOutputData, isPending: isExperimentsOutputPending } =
    useCompareExperimentsColumns(
      {
        datasetId,
        experimentsIds,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useExperimentsFeedbackScoresNames(
      {
        experimentsIds,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const { data: statisticData } = useExperimentItemsStatistic(
    {
      datasetId,
      experimentsIds,
      filters,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;

  const columnsStatistic = useMemo(
    () => statisticData?.stats ?? [],
    [statisticData],
  );

  const dynamicDatasetColumns = useMemo(() => {
    return (data?.columns ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${EXPERIMENT_ITEM_DATASET_PREFIX}.${c.name}`,
        label: c.name,
        columnType: mapDynamicColumnTypesToColumnType(c.types),
      }));
  }, [data]);

  const dynamicOutputColumns = useMemo(() => {
    return (experimentsOutputData?.columns ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${EXPERIMENT_ITEM_OUTPUT_PREFIX}.${c.name}`,
        label: c.name,
        columnType: mapDynamicColumnTypesToColumnType(c.types),
      }));
  }, [experimentsOutputData]);

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? [])
      .filter((c) => c.type !== SCORE_TYPE_EXPERIMENT)
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${COLUMN_FEEDBACK_SCORES_ID}.${c.name}`,
        label: c.name,
        columnType: COLUMN_TYPE.number,
        type: c.type,
      }));
  }, [feedbackScoresData?.scores]);

  const dynamicColumnsIds = useMemo(
    () => [
      ...dynamicDatasetColumns.map((c) => c.id),
      ...dynamicOutputColumns.map((c) => c.id),
      ...dynamicScoresColumns.map((c) => c.id),
      COLUMN_COMMENTS_ID,
      COLUMN_DURATION_ID,
      `${COLUMN_USAGE_ID}.total_tokens`,
      "total_estimated_cost",
    ],
    [dynamicDatasetColumns, dynamicOutputColumns, dynamicScoresColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  return {
    rows,
    total,
    sortableColumns,
    columnsStatistic,
    dynamicDatasetColumns,
    dynamicOutputColumns,
    dynamicScoresColumns,
    dynamicColumnsIds,
    isPending:
      isPending || isFeedbackScoresPending || isExperimentsOutputPending,
    isFetching,
    isPlaceholderData,
    refetchExportData,
  };
};

export default useExperimentItemsData;
