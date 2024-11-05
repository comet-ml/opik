import {
  keepPreviousData,
  QueryFunctionContext,
  useQueries,
} from "@tanstack/react-query";
import { Dataset, Experiment } from "@/types/datasets";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import { useMemo } from "react";
import useExperimentsList, {
  getExperimentsList,
  UseExperimentsListParams,
} from "@/api/datasets/useExperimentsList";
import useDatasetById from "@/api/datasets/useDatasetById";

const RE_FETCH_INTERVAL = 30000;
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
  search?: string;
  page: number;
  size: number;
  groupLimit?: Record<string, number>;
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

const generateMoreRow = (dataset: Dataset) => {
  // TODO lala
  return wrapExperimentRow(
    {
      id: `${dataset.id}_more`,
      dataset_id: dataset.id,
      dataset_name: dataset.name,
    } as Experiment,
    dataset,
  );
};

export default function useGroupedExperimentsList(
  params: UseGroupedExperimentsListParams,
) {
  const hasDataset = Boolean(params.datasetId);

  const { data: deletedDatasetExperiments } = useExperimentsList(
    {
      workspaceName: params.workspaceName,
      search: params.search,
      // TODO lala hardcoded for now
      datasetId: "01920aca-422f-7c78-bc43-db8e7eda4271",
      page: 1,
      size: extractPageSize(DELETED_DATASET_ID, params?.groupLimit),
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: RE_FETCH_INTERVAL,
    },
  );

  const { data: dataset, isPending: isDatasetPending } = useDatasetById(
    {
      datasetId: params.datasetId!,
    },
    { enabled: hasDataset },
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
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: RE_FETCH_INTERVAL,
        enabled: !hasDataset,
      } as never,
    );

  const datasetsData = useMemo(() => {
    return (hasDataset && dataset ? [dataset] : datasetsRowData?.content) || [];
  }, [dataset, hasDataset, datasetsRowData?.content]);

  const total = useMemo(() => {
    const totalDatasets = datasetsRowData?.total || 0;

    return hasDataset
      ? 1
      : hasRemovedDatasetExperiments
        ? totalDatasets + 1
        : totalDatasets;
  }, [datasetsRowData?.total, hasDataset, hasRemovedDatasetExperiments]);

  const datasetsIds = useMemo(() => {
    return datasetsData.map(({ id }) => id);
  }, [datasetsData]);

  const experimentsResponse = useQueries({
    queries: datasetsIds.map((datasetId) => {
      const p: UseExperimentsListParams = {
        workspaceName: params.workspaceName,
        search: params.search,
        datasetId,
        page: 1,
        size: extractPageSize(datasetId, params?.groupLimit),
      };

      return {
        queryKey: ["experiments", p],
        queryFn: (context: QueryFunctionContext) =>
          getExperimentsList(context, p),
        placeholderData: keepPreviousData,
        refetchInterval: RE_FETCH_INTERVAL,
      };
    }),
  });

  // TODO lala should be retested
  const needToShowDeletedDataset =
    hasRemovedDatasetExperiments &&
    !hasDataset &&
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
        const experimentsData = experimentsResponse[index].data ?? {
          content: [],
          total: 0,
        };

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

  // TODO lala test only deleted datasets
  // TODO lala doesn't work in case no datasets
  const isPending =
    (hasDataset ? isDatasetPending : isDatasetsPending) ||
    (experimentsResponse.length > 0 &&
      experimentsResponse.every((r) => r.isPending));

  return {
    data,
    isPending,
  } as UseGroupedExperimentsListResponse;
}
