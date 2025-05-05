import { useCallback, useMemo, useRef } from "react";
import {
  keepPreviousData,
  QueryFunctionContext,
  RefetchOptions,
  useQueries,
} from "@tanstack/react-query";
import isUndefined from "lodash/isUndefined";
import { Dataset } from "@/types/datasets";
import { Optimization } from "@/types/optimizations";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useOptimizationsList, {
  getOptimizationsList,
  UseOptimizationsListParams,
  UseOptimizationsListResponse,
} from "@/api/optimizations/useOptimizationsList";
import useDatasetById from "@/api/datasets/useDatasetById";
import { OPTIMIZATIONS_KEY } from "@/api/api";
import {
  DEFAULT_ITEMS_PER_GROUP,
  DELETED_DATASET_ID,
  GROUPING_COLUMN,
} from "@/constants/grouping";

export const GROUP_SORTING = [
  { id: "last_created_optimization_at", desc: true },
];

export type GroupedOptimization = {
  dataset: Dataset;
  virtual_dataset_id: string;
} & Optimization;

type UseGroupedOptimizationsListParams = {
  workspaceName: string;
  datasetId?: string;
  search?: string;
  page: number;
  size: number;
  groupLimit?: Record<string, number>;
  polling?: boolean;
};

type UseGroupedOptimizationsListResponse = {
  data: {
    content: Array<GroupedOptimization>;
    groupIds: string[];
    total: number;
  };
  datasetsData: Dataset[];
  isPending: boolean;
  refetch: (options?: RefetchOptions) => Promise<unknown>;
};

const extractPageSize = (
  groupId: string,
  groupLimit?: Record<string, number>,
) => {
  return groupLimit?.[groupId] ?? DEFAULT_ITEMS_PER_GROUP;
};

const wrapOptimizationRow = (optimization: Optimization, dataset: Dataset) => {
  return {
    ...optimization,
    dataset,
    [GROUPING_COLUMN]: dataset.id,
  } as GroupedOptimization;
};

const buildMoreRowId = (id: string) => {
  return `more_${id}`;
};

const generateMoreRow = (dataset: Dataset) => {
  return wrapOptimizationRow(
    {
      id: buildMoreRowId(dataset.id),
      dataset_id: dataset.id,
      dataset_name: dataset.name,
    } as Optimization,
    dataset,
  );
};

export default function useGroupedOptimizationsList(
  params: UseGroupedOptimizationsListParams,
) {
  const refetchInterval = params.polling ? 30000 : undefined;
  const optimizationsCache = useRef<
    Record<string, UseOptimizationsListResponse>
  >({});
  const isFilteredByDataset = Boolean(params.datasetId);

  const {
    data: deletedDatasetOptimizations,
    refetch: refetchDeletedDatasetOptimizations,
  } = useOptimizationsList(
    {
      workspaceName: params.workspaceName,
      search: params.search,
      datasetDeleted: true,
      page: 1,
      size: extractPageSize(DELETED_DATASET_ID, params?.groupLimit),
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval,
    },
  );

  const {
    data: dataset,
    isPending: isDatasetPending,
    refetch: refetchDataset,
  } = useDatasetById(
    {
      datasetId: params.datasetId!,
    },
    { enabled: isFilteredByDataset },
  );

  const hasRemovedDatasetOptimizations =
    (deletedDatasetOptimizations?.total || 0) > 0;

  const {
    data: datasetsRowData,
    isPending: isDatasetsPending,
    refetch: refetchDatasetsRowData,
  } = useDatasetsList(
    {
      workspaceName: params.workspaceName,
      page: params.page,
      size: params.size,
      withOptimizationsOnly: true,
      sorting: GROUP_SORTING,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval,
      enabled: !isFilteredByDataset,
    } as never,
  );

  const datasetsData = useMemo(() => {
    return (
      (isFilteredByDataset && dataset ? [dataset] : datasetsRowData?.content) ||
      []
    );
  }, [dataset, isFilteredByDataset, datasetsRowData?.content]);

  const total = useMemo(() => {
    const totalDatasets = datasetsRowData?.total || 0;

    return isFilteredByDataset
      ? 1
      : hasRemovedDatasetOptimizations
        ? totalDatasets + 1
        : totalDatasets;
  }, [
    datasetsRowData?.total,
    isFilteredByDataset,
    hasRemovedDatasetOptimizations,
  ]);

  const datasetsIds = useMemo(() => {
    return datasetsData.map(({ id }) => id);
  }, [datasetsData]);

  const optimizationsResponse = useQueries({
    queries: datasetsIds.map((datasetId) => {
      const p: UseOptimizationsListParams = {
        workspaceName: params.workspaceName,
        search: params.search,
        datasetId,
        page: 1,
        size: extractPageSize(datasetId, params?.groupLimit),
      };

      return {
        queryKey: [OPTIMIZATIONS_KEY, p],
        queryFn: (context: QueryFunctionContext) =>
          getOptimizationsList(context, p),
        refetchInterval,
      };
    }),
  });

  const needToShowDeletedDataset =
    hasRemovedDatasetOptimizations &&
    !isFilteredByDataset &&
    Math.ceil(total / params.size) === params.page;

  const deletedDatasetGroupOptimizations = useMemo(() => {
    if (!needToShowDeletedDataset) return null;
    const hasMoreData =
      extractPageSize(DELETED_DATASET_ID, params.groupLimit) <
      (deletedDatasetOptimizations?.total || 0);
    const deletedDatasetOptimizationsData =
      deletedDatasetOptimizations?.content || [];

    const deletedDataset = {
      id: DELETED_DATASET_ID,
    } as Dataset;

    const retVal = deletedDatasetOptimizationsData.map((e) =>
      wrapOptimizationRow(e, deletedDataset),
    );

    if (hasMoreData) {
      return [...retVal, generateMoreRow(deletedDataset)];
    }

    return retVal;
  }, [
    deletedDatasetOptimizations?.content,
    deletedDatasetOptimizations?.total,
    needToShowDeletedDataset,
    params.groupLimit,
  ]);

  const data = useMemo(() => {
    const content = datasetsData.reduce<GroupedOptimization[]>(
      (acc, dataset, index) => {
        let optimizationsData = optimizationsResponse[index].data;
        if (isUndefined(optimizationsData)) {
          optimizationsData = optimizationsCache.current[dataset.id] ?? {
            content: [],
            total: 0,
          };
        } else {
          optimizationsCache.current[dataset.id] = optimizationsData;
        }

        const hasMoreData =
          extractPageSize(dataset.id, params.groupLimit) <
          optimizationsData.total;

        const retVal = optimizationsData.content.map((e: Optimization) =>
          wrapOptimizationRow(e, dataset),
        );

        if (hasMoreData) {
          return acc.concat([...retVal, generateMoreRow(dataset)]);
        }

        return acc.concat(retVal);
      },
      [],
    );

    const groupIds = datasetsData.map((dataset) => dataset.id);

    if (deletedDatasetGroupOptimizations) {
      groupIds.push(DELETED_DATASET_ID);

      deletedDatasetGroupOptimizations.forEach((e) => {
        content.push(e);
      });
    }

    return {
      content,
      groupIds,
      total,
    };
  }, [
    datasetsData,
    deletedDatasetGroupOptimizations,
    optimizationsResponse,
    params.groupLimit,
    total,
  ]);

  const refetch = useCallback(
    (options: RefetchOptions) => {
      return Promise.all([
        refetchDeletedDatasetOptimizations(options),
        refetchDataset(options),
        refetchDatasetsRowData(options),
        ...optimizationsResponse.map((r) => r.refetch(options)),
      ]);
    },
    [
      optimizationsResponse,
      refetchDataset,
      refetchDatasetsRowData,
      refetchDeletedDatasetOptimizations,
    ],
  );

  const isPending =
    (isFilteredByDataset ? isDatasetPending : isDatasetsPending) ||
    (optimizationsResponse.length > 0 &&
      optimizationsResponse.every((r) => r.isPending) &&
      data.content.length === 0);

  return {
    data,
    isPending,
    refetch,
    datasetsData,
  } as UseGroupedOptimizationsListResponse;
}
