import { useCallback, useMemo, useRef } from "react";
import {
  keepPreviousData,
  QueryFunctionContext,
  RefetchOptions,
  useQueries,
  UseQueryResult,
} from "@tanstack/react-query";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import md5 from "md5";

import {
  Experiment,
  ExperimentsGroupNode,
  ExperimentsAggregations,
  ExperimentsGroupNodeWithAggregations,
} from "@/types/datasets";
import { Filters } from "@/types/filters";
import useExperimentsList, {
  getExperimentsList,
  UseExperimentsListParams,
  UseExperimentsListResponse,
} from "@/api/datasets/useExperimentsList";
import useExperimentsGroups from "@/api/datasets/useExperimentsGroups";
import { SORT_DIRECTION, Sorting } from "@/types/sorting";
import {
  DEFAULT_ITEMS_PER_GROUP,
  GROUP_ID_SEPARATOR,
  GROUP_ROW_TYPE,
  DELETED_ENTITY_LABEL,
} from "@/constants/groups";
import { FlattenGroup, Groups } from "@/types/groups";
import { createFilter } from "@/lib/filters";
import {
  buildRowId,
  isGroupFullyExpanded,
  buildGroupFieldId,
  buildGroupFieldName,
  buildGroupFieldNameForMeta,
} from "@/lib/groups";
import useExperimentsGroupsAggregations from "@/api/datasets/useExperimentsGroupsAggregations";
import useDatasetsList from "@/api/datasets/useDatasetsList";
import useProjectsList from "@/api/projects/useProjectsList";
import { COLUMN_DATASET_ID, COLUMN_PROJECT_ID } from "@/types/shared";

export type GroupedExperiment = Record<string, string> & Experiment;

const DATASETS_SORTING: Sorting = [
  {
    id: "last_created_experiment_at",
    desc: true,
  },
];

const PROJECTS_SORTING: Sorting = [
  {
    id: "last_updated_at",
    desc: true,
  },
];

const MAX_ENTITIES_FOR_SORTING = 1000;

const buildOrderMap = <T extends { id: string }>(
  data: T[] | undefined,
): Record<string, number> | undefined => {
  if (!data) return undefined;
  const orderMap: Record<string, number> = {};
  data.forEach((item, index) => {
    orderMap[item.id] = index;
  });
  return orderMap;
};

type UseGroupedExperimentsListParams = {
  workspaceName: string;
  filters?: Filters;
  sorting?: Sorting;
  groups?: Groups;
  promptId?: string;
  search?: string;
  page: number;
  size: number;
  groupLimit?: Record<string, number>;
  polling?: boolean;
  expandedMap?: Record<string, boolean>;
};

type UseGroupedExperimentsListResponse = {
  data: {
    content: GroupedExperiment[];
    flattenGroups: FlattenGroup[];
    aggregationMap: Record<string, ExperimentsAggregations>;
    sortable_by: string[];
    total: number;
  };
  isPending: boolean;
  isPlaceholderData: boolean;
  isFetching: boolean;
  refetch: (options?: RefetchOptions) => Promise<unknown>;
};

// Pure utility functions
const extractPageSize = (
  groupId: string,
  groupLimit?: Record<string, number>,
): number => {
  return groupLimit?.[groupId] ?? DEFAULT_ITEMS_PER_GROUP;
};

const buildGroupPath = (
  currentGroupsMap: Record<string, ExperimentsGroupNode>,
  groups: Groups,
  parentId: string = "",
  groupIndex: number = 0,
  currentPath: string[] = [],
  accumulatedFilters: Filters = [],
  accumulatedRowGroupData: Record<string, unknown> = {},
  datasetOrderMap?: Record<string, number>,
  projectOrderMap?: Record<string, number>,
): FlattenGroup[] => {
  if (groupIndex >= groups.length) {
    return [];
  }

  const currentGroup = groups[groupIndex];
  const result: FlattenGroup[] = [];

  const entries = Object.entries(currentGroupsMap);
  const isDatasetGroup = currentGroup.field === COLUMN_DATASET_ID;
  const isProjectGroup = currentGroup.field === COLUMN_PROJECT_ID;

  const sortedEntries = entries.sort(([a], [b]) => {
    const labelA = currentGroupsMap[a].label ?? a;
    const labelB = currentGroupsMap[b].label ?? b;

    const isEmptyOrDeletedA = labelA === "" || labelA === DELETED_ENTITY_LABEL;
    const isEmptyOrDeletedB = labelB === "" || labelB === DELETED_ENTITY_LABEL;

    if (isEmptyOrDeletedA && !isEmptyOrDeletedB) return 1; // A goes to the end
    if (!isEmptyOrDeletedA && isEmptyOrDeletedB) return -1; // B goes to the end
    if (isEmptyOrDeletedA && isEmptyOrDeletedB) return 0;

    // If grouping by dataset or project and we have the corresponding order map, use it
    const orderMap = isDatasetGroup
      ? datasetOrderMap
      : isProjectGroup
        ? projectOrderMap
        : undefined;
    if (orderMap) {
      const orderA = orderMap[a] ?? Number.MAX_SAFE_INTEGER;
      const orderB = orderMap[b] ?? Number.MAX_SAFE_INTEGER;
      if (orderA !== orderB) {
        return orderA - orderB;
      }
      // Fall through to label comparison when tied (items beyond MAX_ENTITIES_FOR_SORTING)
    }

    if (currentGroup.direction === SORT_DIRECTION.ASC) {
      return labelA.localeCompare(labelB);
    } else {
      return labelB.localeCompare(labelA);
    }
  });

  sortedEntries.forEach(([value, node]) => {
    const isEmptyListGroup = value === "";
    const currentFilters = [
      ...accumulatedFilters,
      createFilter({
        field: currentGroup.field,
        key: currentGroup.key,
        type: currentGroup.type,
        operator: isEmptyListGroup ? "is_empty" : "=",
        value,
      }),
    ];

    const uniqId = md5(value);
    const metadataFieldName = buildGroupFieldNameForMeta(currentGroup);

    const currentRowGroupData = {
      ...accumulatedRowGroupData,
      [buildGroupFieldName(currentGroup)]: uniqId,
      [metadataFieldName]: {
        value: value,
        label: "" === node.label ? undefined : node.label,
      },
    };

    const metadataPath = [...currentPath, metadataFieldName];
    const id = `${
      parentId ? `${parentId}${GROUP_ID_SEPARATOR}` : ""
    }${buildGroupFieldId(currentGroup, uniqId)}`;

    result.push({
      id,
      rowGroupData: currentRowGroupData,
      filters: currentFilters,
      metadataPath,
      level: groupIndex,
    });

    if (node.groups && Object.keys(node.groups).length > 0) {
      // Intermediate node - recurse into nested groups
      const nestedGroups = buildGroupPath(
        node.groups,
        groups,
        id,
        groupIndex + 1,
        metadataPath,
        currentFilters,
        currentRowGroupData,
        datasetOrderMap,
        projectOrderMap,
      );
      result.push(...nestedGroups);
    }
  });

  return result;
};

const flattenExperimentsGroups = (
  groupsMap: Record<string, ExperimentsGroupNode>,
  groups: Groups,
  datasetOrderMap?: Record<string, number>,
  projectOrderMap?: Record<string, number>,
): FlattenGroup[] => {
  if (!groups.length || !Object.keys(groupsMap).length) {
    return [];
  }

  return buildGroupPath(
    groupsMap,
    groups,
    "",
    0,
    [],
    [],
    {},
    datasetOrderMap,
    projectOrderMap,
  );
};

const buildAggregationMap = (
  currentGroupsMap: Record<string, ExperimentsGroupNodeWithAggregations>,
  groups: Groups,
  parentId: string = "",
  groupIndex: number = 0,
): Record<string, ExperimentsAggregations> => {
  if (groupIndex >= groups.length) {
    return {};
  }

  const currentGroup = groups[groupIndex];
  const result: Record<string, ExperimentsAggregations> = {};

  Object.entries(currentGroupsMap).forEach(([value, node]) => {
    const uniqId = md5(value);
    const id = `${
      parentId ? `${parentId}${GROUP_ID_SEPARATOR}` : ""
    }${buildGroupFieldId(currentGroup, uniqId)}`;

    // Add aggregation data for this group if available
    if (node.aggregations) {
      result[id] = node.aggregations;
    }

    // Recursively process nested groups
    if (node.groups && Object.keys(node.groups).length > 0) {
      const nestedAggregations = buildAggregationMap(
        node.groups,
        groups,
        id,
        groupIndex + 1,
      );

      Object.assign(result, nestedAggregations);
    }
  });

  return result;
};

const wrapExperimentWithGroupData = (
  experiment: Experiment,
  group: FlattenGroup,
): GroupedExperiment =>
  ({
    ...experiment,
    ...group.rowGroupData,
  }) as GroupedExperiment;

const generateMoreRow = (group: FlattenGroup): GroupedExperiment => {
  return wrapExperimentWithGroupData(
    { id: buildRowId(GROUP_ROW_TYPE.MORE, group.id) } as Experiment,
    group,
  );
};

const generatePendingRow = (group: FlattenGroup): GroupedExperiment => {
  return wrapExperimentWithGroupData(
    { id: buildRowId(GROUP_ROW_TYPE.PENDING, group.id) } as Experiment,
    group,
  );
};

const generateErrorRow = (
  group: FlattenGroup,
  error?: string,
): GroupedExperiment => {
  return wrapExperimentWithGroupData(
    {
      id: buildRowId(GROUP_ROW_TYPE.ERROR, group.id),
      ...(error && { error }),
    } as Experiment,
    group,
  );
};

const useExperimentsCache = () => {
  return useRef<Record<string, UseExperimentsListResponse>>({});
};

export default function useGroupedExperimentsList(
  params: UseGroupedExperimentsListParams,
): UseGroupedExperimentsListResponse {
  const refetchInterval = params.polling ? 30000 : undefined;
  const experimentsCache = useExperimentsCache();
  const groups = useMemo(() => params.groups ?? [], [params.groups]);
  const hasGroups = Boolean(groups?.length);
  const isGroupingByDataset = useMemo(
    () => groups?.some((g) => g.field === COLUMN_DATASET_ID) ?? false,
    [groups],
  );
  const isGroupingByProject = useMemo(
    () => groups?.some((g) => g.field === COLUMN_PROJECT_ID) ?? false,
    [groups],
  );

  // Extract project_id from filters and pass it as a separate parameter
  // because project_id filtering requires a special SQL query (join with traces)
  const { projectId, projectDeleted, filtersWithoutProjectId } = useMemo(() => {
    const projectFilter = params.filters?.find(
      (f) => f.field === COLUMN_PROJECT_ID,
    );
    const otherFilters = params.filters?.filter(
      (f) => f.field !== COLUMN_PROJECT_ID,
    );

    const projectIdValue = projectFilter?.value as string | undefined;

    // Check if this is an orphan project filter
    // For orphan projects, the backend returns empty string as the group key
    const isOrphanProjectFilter = projectFilter && projectIdValue === "";

    return {
      projectId: isOrphanProjectFilter ? undefined : projectIdValue,
      projectDeleted: isOrphanProjectFilter ? true : undefined,
      filtersWithoutProjectId: otherFilters,
    };
  }, [params.filters]);

  const {
    data: groupsData,
    isPending: isGroupsPending,
    isPlaceholderData: isGroupsPlaceholderData,
    isFetching: isGroupsFetching,
    refetch: refetchGroups,
  } = useExperimentsGroups(
    {
      workspaceName: params.workspaceName,
      filters: filtersWithoutProjectId,
      groups: groups!,
      search: params.search,
      promptId: params.promptId,
      projectId,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval,
      enabled: hasGroups,
    },
  );

  const { data: groupsAggregationsData, refetch: refetchGroupsAggregations } =
    useExperimentsGroupsAggregations(
      {
        workspaceName: params.workspaceName,
        filters: filtersWithoutProjectId,
        groups: groups!,
        search: params.search,
        promptId: params.promptId,
        projectId,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval,
        enabled: hasGroups,
      },
    );

  const {
    data: datasetsData,
    isPending: isDatasetsPending,
    refetch: refetchDatasets,
  } = useDatasetsList(
    {
      workspaceName: params.workspaceName,
      page: 1,
      size: MAX_ENTITIES_FOR_SORTING,
      withExperimentsOnly: true,
      sorting: DATASETS_SORTING,
    },
    {
      placeholderData: keepPreviousData,
      enabled: hasGroups && isGroupingByDataset,
      refetchInterval,
    },
  );

  const {
    data: projectsData,
    isPending: isProjectsPending,
    refetch: refetchProjects,
  } = useProjectsList(
    {
      workspaceName: params.workspaceName,
      page: 1,
      size: MAX_ENTITIES_FOR_SORTING,
      sorting: PROJECTS_SORTING,
    },
    {
      placeholderData: keepPreviousData,
      enabled: hasGroups && isGroupingByProject,
      refetchInterval,
    },
  );

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useExperimentsList(
      {
        workspaceName: params.workspaceName,
        filters: filtersWithoutProjectId,
        sorting: params.sorting,
        search: params.search,
        promptId: params.promptId,
        projectId,
        projectDeleted,
        page: params.page,
        size: params.size,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval,
        enabled: !hasGroups,
      },
    );

  const groupsMap = useMemo(() => groupsData?.content ?? {}, [groupsData]);

  const datasetOrderMap = useMemo(
    () => buildOrderMap(datasetsData?.content),
    [datasetsData?.content],
  );

  const projectOrderMap = useMemo(
    () => buildOrderMap(projectsData?.content),
    [projectsData?.content],
  );

  const flattenGroups = useMemo(
    () =>
      flattenExperimentsGroups(
        groupsMap,
        groups,
        datasetOrderMap,
        projectOrderMap,
      ),
    [groupsMap, groups, datasetOrderMap, projectOrderMap],
  );

  const aggregationMap = useMemo(() => {
    if (!groupsAggregationsData?.content || !groups.length) {
      return {};
    }

    return buildAggregationMap(groupsAggregationsData.content, groups);
  }, [groupsAggregationsData, groups]);

  const flattenDeepestGroups = useMemo(() => {
    return flattenGroups.filter((group) => group.level === groups.length - 1);
  }, [flattenGroups, groups]);

  const expandedGroups = useMemo(() => {
    return flattenDeepestGroups.filter((group) =>
      isGroupFullyExpanded(group, params.expandedMap),
    );
  }, [flattenDeepestGroups, params.expandedMap]);

  const experimentsResponses = useQueries({
    queries: expandedGroups.map(({ id, filters, rowGroupData }) => {
      // Combine top-level filters with group-specific filters
      const combinedFilters = [...(params.filters ?? []), ...filters];

      // Extract project_id from combined filters and pass it as a separate parameter
      // (project_id requires a special SQL query with joins)
      const projectFilter = combinedFilters.find(
        (f) => f.field === COLUMN_PROJECT_ID,
      );
      const filtersWithoutProject = combinedFilters.filter(
        (f) => f.field !== COLUMN_PROJECT_ID,
      );

      // Check if this is an orphan project (deleted project) by looking at the group metadata
      // The backend returns "__DELETED" as the label for orphan entities
      const projectGroup = groups.find((g) => g.field === COLUMN_PROJECT_ID);
      const projectMeta = projectGroup
        ? (rowGroupData[buildGroupFieldNameForMeta(projectGroup)] as
            | { value: string; label?: string }
            | undefined)
        : undefined;
      const isOrphanProject = projectMeta?.label === DELETED_ENTITY_LABEL;

      // Get project ID - prefer filter value, fall back to group metadata value
      const projectIdValue = (projectFilter?.value ?? projectMeta?.value) as
        | string
        | undefined;

      const queryParams: UseExperimentsListParams = {
        workspaceName: params.workspaceName,
        filters: filtersWithoutProject,
        sorting: params.sorting,
        search: params.search,
        promptId: params.promptId,
        // Don't send projectId if it's an orphan project, use projectDeleted flag instead
        projectId: isOrphanProject ? undefined : projectIdValue,
        projectDeleted: isOrphanProject || undefined,
        page: 1,
        size: extractPageSize(id, params.groupLimit),
      };

      return {
        queryKey: ["experiments", queryParams],
        queryFn: (context: QueryFunctionContext) =>
          getExperimentsList(context, queryParams),
        placeholderData: () => experimentsCache.current[id],
        refetchInterval,
      };
    }),
    combine: (results) => {
      return results.reduce<
        Record<string, UseQueryResult<UseExperimentsListResponse>>
      >((acc, result, idx) => {
        acc[expandedGroups[idx].id] = result;
        return acc;
      }, {});
    },
  });

  const groupedData = useMemo(() => {
    let sortableBy: string[] | undefined;

    const content = flattenDeepestGroups.reduce<GroupedExperiment[]>(
      (acc, flattenGroup) => {
        const queryResponse = experimentsResponses[flattenGroup.id];
        const isQueryPending = queryResponse?.isPending;
        const hasQueryError = queryResponse?.isError;
        let experimentsData = queryResponse?.data;

        if (isUndefined(experimentsData) && experimentsCache.current) {
          experimentsData = experimentsCache.current[flattenGroup.id] ?? {
            content: [],
            total: 0,
          };
        } else if (experimentsData && experimentsCache) {
          experimentsCache.current[flattenGroup.id] = experimentsData;
        }

        if (hasQueryError) {
          return acc.concat(
            generateErrorRow(
              flattenGroup,
              get(
                queryResponse?.error,
                ["response", "data", "message"],
                queryResponse?.error?.message,
              ),
            ),
          );
        }

        // Extract sortable fields from any response that has them
        if (!sortableBy && experimentsData?.sortable_by?.length) {
          sortableBy = experimentsData.sortable_by;
        }

        const wrappedExperiments = (experimentsData?.content || []).map(
          (experiment: Experiment) =>
            wrapExperimentWithGroupData(experiment, flattenGroup),
        );

        if (
          isQueryPending ||
          !experimentsData ||
          !experimentsData.content.length
        ) {
          return acc.concat([
            ...wrappedExperiments,
            generatePendingRow(flattenGroup),
          ]);
        }

        const hasMoreData =
          extractPageSize(flattenGroup.id, params.groupLimit) <
          experimentsData.total;

        if (hasMoreData) {
          return acc.concat([
            ...wrappedExperiments,
            generateMoreRow(flattenGroup),
          ]);
        }

        return acc.concat(wrappedExperiments);
      },
      [],
    );

    return {
      content,
      flattenGroups,
      sortable_by: sortableBy ?? [],
      total: content.length,
    };
  }, [
    flattenDeepestGroups,
    experimentsResponses,
    flattenGroups,
    params.groupLimit,
    experimentsCache,
  ]);

  const groupedRefetch = useCallback(
    (options?: RefetchOptions) => {
      const refetchPromises: Promise<unknown>[] = [
        refetchGroups(options),
        refetchGroupsAggregations(options),
        ...Object.values(experimentsResponses).map((response) =>
          response.refetch(options),
        ),
      ];

      // Only refetch datasets when grouping by dataset
      if (isGroupingByDataset) {
        refetchPromises.push(refetchDatasets(options));
      }

      // Only refetch projects when grouping by project
      if (isGroupingByProject) {
        refetchPromises.push(refetchProjects(options));
      }

      return Promise.all(refetchPromises);
    },
    [
      experimentsResponses,
      refetchGroups,
      refetchGroupsAggregations,
      refetchDatasets,
      refetchProjects,
      isGroupingByDataset,
      isGroupingByProject,
    ],
  );

  const transformedData = useMemo(
    () => ({
      content: hasGroups
        ? groupedData.content
        : (data?.content as GroupedExperiment[]) ?? [],
      flattenGroups: hasGroups ? groupedData.flattenGroups : [],
      aggregationMap: hasGroups ? aggregationMap : {},
      sortable_by: hasGroups
        ? groupedData.sortable_by
        : data?.sortable_by ?? [],
      total: hasGroups ? groupedData.total : data?.total ?? 0,
    }),
    [
      hasGroups,
      groupedData.content,
      groupedData.flattenGroups,
      groupedData.sortable_by,
      groupedData.total,
      data?.content,
      data?.sortable_by,
      data?.total,
      aggregationMap,
    ],
  );

  // When groups are active, we're only pending if the initial groups/datasets/projects queries are pending
  // The individual experiment queries for expanded groups will load separately
  // Only check isDatasetsPending/isProjectsPending if the respective queries are actually enabled
  const groupedIsPending =
    isGroupsPending ||
    (isGroupingByDataset && isDatasetsPending) ||
    (isGroupingByProject && isProjectsPending);

  // For grouped mode, check if the groups query is fetching or any expanded group queries are fetching
  const groupedIsFetching =
    isGroupsFetching ||
    Object.values(experimentsResponses).some((r) => r.isFetching);

  // For grouped mode, check if any query is showing placeholder data
  const groupedIsPlaceholderData =
    isGroupsPlaceholderData ||
    Object.values(experimentsResponses).some((r) => r.isPlaceholderData);

  return {
    data: transformedData,
    isPending: hasGroups ? groupedIsPending : isPending,
    isPlaceholderData: hasGroups ? groupedIsPlaceholderData : isPlaceholderData,
    isFetching: hasGroups ? groupedIsFetching : isFetching,
    refetch: hasGroups ? groupedRefetch : refetch,
  };
}
