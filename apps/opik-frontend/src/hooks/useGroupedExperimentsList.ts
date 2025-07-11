import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  keepPreviousData,
  QueryFunctionContext,
  RefetchOptions,
  useQueries,
  useQuery,
} from "@tanstack/react-query";
import isUndefined from "lodash/isUndefined";
import { Dataset, Experiment } from "@/types/datasets";
import { Filters } from "@/types/filters";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useExperimentsList, {
  getExperimentsList,
  UseExperimentsListParams,
  UseExperimentsListResponse,
} from "@/api/datasets/useExperimentsList";
import useDatasetById from "@/api/datasets/useDatasetById";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import {
  DEFAULT_ITEMS_PER_GROUP,
  DELETED_DATASET_ID,
  GROUPING_COLUMN,
} from "@/constants/grouping";
import api from "@/api/api";

export const GROUP_SORTING = [{ id: "last_created_experiment_at", desc: true }];

export type GroupedExperiment = {
  dataset: Dataset;
  virtual_dataset_id: string;
} & Experiment;

export type GroupedExperimentGroup = {
  dataset: Dataset;
  experiments: Experiment[];
  total: number;
};

type UseGroupedExperimentsListParams = {
  workspaceName: string;
  filters?: Filters;
  sorting?: Sorting;
  datasetId?: string;
  promptId?: string;
  search?: string;
  page: number;
  size: number;
  groupLimit?: Record<string, number>;
  polling?: boolean;
};

type UseGroupedExperimentsListResponse = {
  data: {
    groups: Array<GroupedExperimentGroup>;
    groupIds: string[];
    sortable_by: string[];
    total: number;
  };
  isPending: boolean;
  refetch: (options?: RefetchOptions) => Promise<unknown>;
};

const extractPageSize = (
  groupId: string,
  groupLimit?: Record<string, number>,
) => {
  return groupLimit?.[groupId] ?? DEFAULT_ITEMS_PER_GROUP;
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
  // Map params to backend API query params
  const { workspaceName, filters, sorting, datasetId, promptId, page, size, search } = params;
  const queryKey = ["grouped-experiments", { workspaceName, filters, sorting, datasetId, promptId, page, size, search }];
  const { data, isPending, refetch } = useQuery({
    queryKey,
    queryFn: async () => {
      const { data } = await api.get("/v1/private/experiments/grouped", {
        params: {
          page,
          size,
          ...(datasetId && { dataset_id: datasetId }),
          ...(promptId && { prompt_id: promptId }),
          ...processSorting(sorting),
          ...(filters && { filters: JSON.stringify(filters) }),
          ...(search && { name: search }),
        },
      });
      // Build a flat array of experiments with grouping fields for the table
      const experiments: GroupedExperiment[] = (data.content || []).flatMap((group: any) =>
        (group.experiments || []).map((exp: any) => ({
          ...exp,
          dataset_id: exp.dataset_id,
          dataset_name: exp.dataset_name,
          name: exp.name,
          virtual_dataset_id: exp.dataset_id,
          dataset: group.dataset && group.dataset.id ? group.dataset : { id: exp.dataset_id, name: exp.dataset_name },
        }))
      );
      const groups = (data.content || []).map((group: any) => {
        // Get first experiment to use as fallback for dataset info
        const firstExp = (group.experiments || [])[0];
        return {
          dataset: group.dataset && group.dataset.id ? group.dataset : { id: firstExp?.dataset_id, name: firstExp?.dataset_name },
          experiments: (group.experiments || []).map((exp: any) => ({
            ...exp,
            dataset_id: exp.dataset_id,
            dataset_name: exp.dataset_name,
            name: exp.name,
            virtual_dataset_id: exp.dataset_id,
            dataset: group.dataset && group.dataset.id ? group.dataset : { id: exp.dataset_id, name: exp.dataset_name },
          })),
          total: group.total,
        };
      });
      const groupIds = groups.map((g: GroupedExperimentGroup) => g.dataset.id);
      return {
        experiments,
        groups,
        groupIds,
        sortable_by: data.sortable_by || [],
        total: data.total,
      };
    },
    placeholderData: undefined,
  });
  return {
    data,
    isPending,
    refetch,
  } as UseGroupedExperimentsListResponse & { data: any };
}
