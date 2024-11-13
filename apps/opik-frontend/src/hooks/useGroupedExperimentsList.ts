import { useMemo, useRef } from "react";
import {
  keepPreviousData,
  QueryFunctionContext,
  useQueries,
} from "@tanstack/react-query";
import isUndefined from "lodash/isUndefined";
import { Dataset, Experiment } from "@/types/datasets";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useExperimentsList, {
  getExperimentsList,
  UseExperimentsListParams,
  UseExperimentsListResponse,
} from "@/api/datasets/useExperimentsList";
import useDatasetById from "@/api/datasets/useDatasetById";

export const DELETED_DATASET_ID = "deleted_dataset_id";
export const DEFAULT_EXPERIMENTS_PER_GROUP = 25;
export const GROUPING_COLUMN = "virtual_dataset_id";

export type GroupedExperiment = {
  dataset: Dataset;
  virtual_dataset_id: string;
} & Experiment;

type UseGroupedExperimentsListParams = {
  workspaceName: string;
  datasetId?: string;
  promptId?: string;
  search?: string;
  page: number;
  size: number;
  groupLimit?: Record<string, number>;
  pooling?: boolean;
};

type UseGroupedExperimentsListResponse = {
  data: {
    content: Array<GroupedExperiment>;
    groupIds: string[];
    total: number;
  };
  isPending: boolean;
  isLoading: boolean;
  isError: boolean;
};

const extractPageSize = (
  groupId: string,
  groupLimit?: Record<string, number>,
) => {
  return groupLimit?.[groupId] ?? DEFAULT_EXPERIMENTS_PER_GROUP;
};

const wrapExperimentRow = (experiment: Experiment, dataset: Dataset) => {
  return {
    ...experiment,
    dataset,
    [GROUPING_COLUMN]: dataset.id,
  } as GroupedExperiment;
};

const buildMoreRowId = (id: string) => {
  return `more_${id}`;
};

export const checkIsMoreRowId = (id: string) => {
  return /^more_/.test(id);
};

const generateMoreRow = (dataset: Dataset) => {
  return wrapExperimentRow(
    {
      id: buildMoreRowId(dataset.id),
      dataset_id: dataset.id,
      dataset_name: dataset.name,
    } as Experiment,
    dataset,
  );
};

export default function useGroupedExperimentsList(
  params: UseGroupedExperimentsListParams,
) {
  const refetchInterval = params.pooling ? 30000 : undefined;
  const experimentsCache = useRef<Record<string, UseExperimentsListResponse>>(
    {},
  );
  const isFilteredByDataset = Boolean(params.datasetId);

  const { data: deletedDatasetExperiments } = useExperimentsList(
    {
      workspaceName: params.workspaceName,
      search: params.search,
      datasetDeleted: true,
      promptId: params.promptId,
      page: 1,
      size: extractPageSize(DELETED_DATASET_ID, params?.groupLimit),
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval,
    },
  );

  const { data: dataset, isPending: isDatasetPending } = useDatasetById(
    {
      datasetId: params.datasetId!,
    },
    { enabled: isFilteredByDataset },
  );

  const hasRemovedDatasetExperiments =
    (deletedDatasetExperiments?.total || 0) > 0;

  const { data: datasetsRowData, isPending: isDatasetsPending } =
    useDatasetsList(
      {
        workspaceName: params.workspaceName,
        page: params.page,
        size: params.size,
        withExperimentsOnly: true,
        promptId: params.promptId,
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
      : hasRemovedDatasetExperiments
        ? totalDatasets + 1
        : totalDatasets;
  }, [
    datasetsRowData?.total,
    isFilteredByDataset,
    hasRemovedDatasetExperiments,
  ]);

  const datasetsIds = useMemo(() => {
    return datasetsData.map(({ id }) => id);
  }, [datasetsData]);

  const experimentsResponse = useQueries({
    queries: datasetsIds.map((datasetId) => {
      const p: UseExperimentsListParams = {
        workspaceName: params.workspaceName,
        search: params.search,
        datasetId,
        promptId: params.promptId,
        page: 1,
        size: extractPageSize(datasetId, params?.groupLimit),
      };

      return {
        queryKey: ["experiments", p],
        queryFn: (context: QueryFunctionContext) =>
          getExperimentsList(context, p),
        refetchInterval,
      };
    }),
  });

  const needToShowDeletedDataset =
    hasRemovedDatasetExperiments &&
    !isFilteredByDataset &&
    Math.ceil(total / params.size) === params.page;

  const deletedDatasetGroupExperiments = useMemo(() => {
    if (!needToShowDeletedDataset) return null;
    const hasMoreData =
      extractPageSize(DELETED_DATASET_ID, params.groupLimit) <
      (deletedDatasetExperiments?.total || 0);
    const deletedDatasetExperimentsData =
      deletedDatasetExperiments?.content || [];

    const deletedDataset = {
      id: DELETED_DATASET_ID,
    } as Dataset;

    const retVal = deletedDatasetExperimentsData.map((e) =>
      wrapExperimentRow(e, deletedDataset),
    );

    if (hasMoreData) {
      return [...retVal, generateMoreRow(deletedDataset)];
    }

    return retVal;
  }, [
    deletedDatasetExperiments?.content,
    deletedDatasetExperiments?.total,
    needToShowDeletedDataset,
    params.groupLimit,
  ]);

  const data = useMemo(() => {
    const content = datasetsData.reduce<GroupedExperiment[]>(
      (acc, dataset, index) => {
        let experimentsData = experimentsResponse[index].data;
        if (isUndefined(experimentsData)) {
          experimentsData = experimentsCache.current[dataset.id] ?? {
            content: [],
            total: 0,
          };
        } else {
          experimentsCache.current[dataset.id] = experimentsData;
        }

        const hasMoreData =
          extractPageSize(dataset.id, params.groupLimit) <
          experimentsData.total;

        const retVal = experimentsData.content.map((e: Experiment) =>
          wrapExperimentRow(e, dataset),
        );

        if (hasMoreData) {
          return acc.concat([...retVal, generateMoreRow(dataset)]);
        }

        return acc.concat(retVal);
      },
      [],
    );

    const groupIds = datasetsData.map((dataset) => dataset.id);

    if (deletedDatasetGroupExperiments) {
      groupIds.push(DELETED_DATASET_ID);

      deletedDatasetGroupExperiments.forEach((e) => {
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
    deletedDatasetGroupExperiments,
    experimentsResponse,
    params.groupLimit,
    total,
  ]);

  const isPending =
    (isFilteredByDataset ? isDatasetPending : isDatasetsPending) ||
    (experimentsResponse.length > 0 &&
      experimentsResponse.every((r) => r.isPending) &&
      data.content.length === 0);

  return {
    data,
    isPending,
  } as UseGroupedExperimentsListResponse;
}
