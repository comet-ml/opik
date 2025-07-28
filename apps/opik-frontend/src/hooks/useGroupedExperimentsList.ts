import { useCallback, useMemo, useRef } from "react";
import {
  keepPreviousData,
  QueryFunctionContext,
  RefetchOptions,
  useQueries,
} from "@tanstack/react-query";
import isUndefined from "lodash/isUndefined";
import { Experiment } from "@/types/datasets";
import { Filters } from "@/types/filters";

import useExperimentsList, {
  getExperimentsList,
  UseExperimentsListParams,
  UseExperimentsListResponse,
} from "@/api/datasets/useExperimentsList";
import useExperimentsGroups from "@/api/datasets/useExperimentsGroups";
import { ExperimentsGroupNode } from "@/types/datasets";
import { Sorting, SORT_DIRECTION } from "@/types/sorting";
import { DEFAULT_ITEMS_PER_GROUP } from "@/constants/groups";
import { Groups } from "@/types/groups";
import { createFilter } from "@/lib/filters";
import {
  buildGroupFieldsName,
  buildMoreRowId,
} from "@/components/shared/DataTable/utils";

// Types
type GroupPathEntry = {
  value: string;
  label: string;
};

type AbstractGroup = {
  id: string;
  name: string;
  field: string;
  filters: Filters;
  path: GroupPathEntry[];
  level: number;
};

export type GroupedExperiment = Record<string, string> & Experiment;

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
};

type UseGroupedExperimentsListResponse = {
  data: {
    content: GroupedExperiment[];
    groupIds: string[];
    sortable_by: string[];
    total: number;
  };
  isPending: boolean;
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
  groupIndex: number,
  currentPath: GroupPathEntry[] = [],
  accumulatedFilters: Filters = [],
  maxDepth: number,
): AbstractGroup[] => {
  if (groupIndex >= groups.length) {
    return [];
  }

  const currentGroup = groups[groupIndex];
  const result: AbstractGroup[] = [];

  // Sort entries based on the direction specified in the group configuration
  const entries = Object.entries(currentGroupsMap);
  const sortedEntries = entries.sort(([a], [b]) => {
    if (currentGroup.direction === SORT_DIRECTION.ASC) {
      return a.localeCompare(b);
    } else {
      return b.localeCompare(a);
    }
  });

  sortedEntries.forEach(([value, node]) => {
    const label = node.label ?? value;
    const currentFilters = [
      ...accumulatedFilters,
      createFilter({
        field: currentGroup.field,
        key: currentGroup.key,
        operator: "=",
        value,
      }),
    ];

    const currentPathEntry = { value, label };
    const newPath = [...currentPath, currentPathEntry];

    // Only create experiment queries for groups at the maximum depth
    if (groupIndex === maxDepth) {
      result.push({
        id: newPath.map((p) => p.value).join("::"),
        name: newPath.map((p) => p.label).join(" > "),
        field: currentGroup.field,
        filters: currentFilters,
        path: newPath,
        level: groupIndex,
      });
    } else if (node.groups && Object.keys(node.groups).length > 0) {
      // Intermediate node - recurse into nested groups
      const nestedGroups = buildGroupPath(
        node.groups,
        groups,
        groupIndex + 1,
        newPath,
        currentFilters,
        maxDepth,
      );
      result.push(...nestedGroups);
    }
  });

  return result;
};

const buildExpandedGroups = (
  groupsMap: Record<string, ExperimentsGroupNode>,
  groups: Groups,
): AbstractGroup[] => {
  if (!groups.length || !Object.keys(groupsMap).length) {
    return [];
  }

  return buildGroupPath(groupsMap, groups, 0, [], [], groups.length - 1);
};

const wrapExperimentWithGroupData = (
  experiment: Experiment,
  group: AbstractGroup,
  allGroups: Groups,
): GroupedExperiment => {
  const wrappedExperiment = { ...experiment } as GroupedExperiment;

  // Add group fields for all levels in the hierarchy
  group.path.forEach((pathEntry, index) => {
    const groupField = allGroups[index];
    if (groupField) {
      wrappedExperiment[buildGroupFieldsName(groupField.field)] =
        pathEntry.value;
    }
  });

  return wrappedExperiment;
};

const generateMoreRow = (
  group: AbstractGroup,
  allGroups: Groups,
): GroupedExperiment => {
  return wrapExperimentWithGroupData(
    { id: buildMoreRowId(group.id) } as Experiment,
    group,
    allGroups,
  );
};

const transformExperimentsData = (
  expandedGroups: AbstractGroup[],
  experimentsResponses: Array<{
    data?: UseExperimentsListResponse;
    isPending: boolean;
  }>,
  groups: Groups,
  groupsMap: Record<string, ExperimentsGroupNode>,
  groupLimit?: Record<string, number>,
  experimentsCache?: Record<string, UseExperimentsListResponse>,
) => {
  let sortableBy: string[] | undefined;

  const content = expandedGroups.reduce<GroupedExperiment[]>(
    (acc, group, index) => {
      let experimentsData = experimentsResponses[index]?.data;

      if (isUndefined(experimentsData) && experimentsCache) {
        experimentsData = experimentsCache[group.id] ?? {
          content: [],
          total: 0,
        };
      } else if (experimentsData && experimentsCache) {
        experimentsCache[group.id] = experimentsData;
      }

      if (!experimentsData) {
        return acc;
      }

      // Extract sortable fields from any response that has them
      if (!sortableBy && experimentsData.sortable_by?.length) {
        sortableBy = experimentsData.sortable_by;
      }

      const hasMoreData =
        extractPageSize(group.id, groupLimit) < experimentsData.total;

      const wrappedExperiments = experimentsData.content.map(
        (experiment: Experiment) =>
          wrapExperimentWithGroupData(experiment, group, groups),
      );

      if (hasMoreData) {
        return acc.concat([
          ...wrappedExperiments,
          generateMoreRow(group, groups),
        ]);
      }

      return acc.concat(wrappedExperiments);
    },
    [],
  );

  return {
    content,
    groupIds: Object.keys(groupsMap),
    sortable_by: sortableBy ?? [],
    total: content.length, // TODO: This should come from the API when available
  };
};

const useExperimentsCache = () => {
  return useRef<Record<string, UseExperimentsListResponse>>({});
};

// Main hook
export default function useGroupedExperimentsList(
  params: UseGroupedExperimentsListParams,
): UseGroupedExperimentsListResponse {
  const refetchInterval = params.polling ? 30000 : undefined;
  const experimentsCache = useExperimentsCache();
  const groups = useMemo(() => params.groups ?? [], [params.groups]);
  const hasGroups = Boolean(groups?.length);

  const {
    data: groupsData,
    isPending: isGroupsPending,
    refetch: refetchGroups,
  } = useExperimentsGroups(
    {
      workspaceName: params.workspaceName,
      filters: params.filters,
      groups: groups!,
      search: params.search,
      promptId: params.promptId,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval,
      enabled: hasGroups,
    },
  );

  const { data, isPending, refetch } = useExperimentsList(
    {
      workspaceName: params.workspaceName,
      filters: params.filters,
      sorting: params.sorting,
      search: params.search,
      promptId: params.promptId,
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

  const expandedGroups = useMemo(
    () => buildExpandedGroups(groupsMap, groups),
    [groupsMap, groups],
  );

  const experimentsQueries = useMemo(
    () =>
      expandedGroups.map(({ id, filters }) => {
        const queryParams: UseExperimentsListParams = {
          workspaceName: params.workspaceName,
          filters: [...(params.filters ?? []), ...filters],
          sorting: params.sorting,
          search: params.search,
          promptId: params.promptId,
          page: 1,
          size: extractPageSize(id, params.groupLimit),
        };

        return {
          queryKey: ["experiments", queryParams],
          queryFn: (context: QueryFunctionContext) =>
            getExperimentsList(context, queryParams),
          refetchInterval,
        };
      }),
    [expandedGroups, params, refetchInterval],
  );

  const experimentsResponses = useQueries({ queries: experimentsQueries });

  const groupedData = useMemo(
    () =>
      transformExperimentsData(
        expandedGroups,
        experimentsResponses,
        groups,
        groupsMap,
        params.groupLimit,
        experimentsCache.current,
      ),
    [
      expandedGroups,
      experimentsResponses,
      groups,
      groupsMap,
      params.groupLimit,
      experimentsCache,
    ],
  );

  const groupedRefetch = useCallback(
    (options?: RefetchOptions) => {
      return Promise.all([
        refetchGroups(options),
        ...experimentsResponses.map((response) => response.refetch(options)),
      ]);
    },
    [experimentsResponses, refetchGroups],
  );

  // Transform non-grouped data to match expected structure
  const transformedData = useMemo(() => {
    if (hasGroups) {
      return groupedData;
    }

    if (!data) {
      return {
        content: [],
        groupIds: [],
        sortable_by: [],
        total: 0,
      };
    }

    return {
      content: data.content as GroupedExperiment[],
      groupIds: [],
      sortable_by: data.sortable_by ?? [],
      total: data.total,
    };
  }, [hasGroups, groupedData, data]);

  return {
    data: transformedData,
    isPending: hasGroups ? isGroupsPending : isPending,
    refetch: hasGroups ? groupedRefetch : refetch,
  };
}
