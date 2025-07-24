import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  keepPreviousData,
  QueryFunctionContext,
  RefetchOptions,
  useQueries,
} from "@tanstack/react-query";
import isUndefined from "lodash/isUndefined";
import { Experiment } from "@/types/datasets";
import { Filters } from "@/types/filters";

import {
  getExperimentsList,
  UseExperimentsListParams,
  UseExperimentsListResponse,
} from "@/api/datasets/useExperimentsList";
import useExperimentsGroups from "@/api/datasets/useExperimentsGroups";
import { Sorting } from "@/types/sorting";
import { DEFAULT_ITEMS_PER_GROUP } from "@/constants/groups";
import { Groups } from "@/types/groups";
import { createFilter } from "@/lib/filters";
import {
  buildGroupFieldsName,
  buildMoreRowId,
} from "@/components/shared/DataTable/utils";

type AbstractGroup = {
  id: string;
  name: string;
  field: string;
  filters: Filters;
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

const extractPageSize = (
  groupId: string,
  groupLimit?: Record<string, number>,
) => {
  return groupLimit?.[groupId] ?? DEFAULT_ITEMS_PER_GROUP;
};

const wrapExperimentRow = (experiment: Experiment, group: AbstractGroup) => {
  return {
    ...experiment,
    [buildGroupFieldsName(group.field)]: group.id,
  } as GroupedExperiment;
};

const generateMoreRow = (group: AbstractGroup) => {
  return wrapExperimentRow(
    {
      id: buildMoreRowId(group.id),
    } as Experiment,
    group,
  );
};

export default function useGroupedExperimentsList(
  params: UseGroupedExperimentsListParams,
) {
  const refetchInterval = params.polling ? 30000 : undefined;
  const experimentsCache = useRef<Record<string, UseExperimentsListResponse>>(
    {},
  );

  const {
    data: groupsData,
    isPending: isGroupsPending,
    refetch: refetchGroups,
  } = useExperimentsGroups(
    {
      workspaceName: params.workspaceName,
      filters: params.filters,
      groups: params.groups!,
      search: params.search,
      promptId: params.promptId,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval,
      enabled: Boolean(params.groups?.length),
    },
  );

  const groupsMap = useMemo(() => groupsData?.content ?? {}, [groupsData]);
  const total = 100500; // TODO lala
  const groups = useMemo(() => params.groups ?? [], [params.groups]);
  const expandedGroups = useMemo(() => {
    const group = groups[0];
    return Object.entries(groupsMap).map(([value, node]) => {
      return {
        id: value,
        name: node.label ?? value,
        field: group.field,
        filters: [
          createFilter({
            field: group.field,
            key: group.key,
            operator: "=",
            value,
          }),
        ],
      };
    });
  }, [groups, groupsMap]);

  const experimentsResponse = useQueries({
    queries: expandedGroups.map(({ id, filters }) => {
      const p: UseExperimentsListParams = {
        workspaceName: params.workspaceName,
        filters: [...(params.filters ? params.filters : []), ...filters],
        sorting: params.sorting,
        search: params.search,
        promptId: params.promptId,
        page: 1,
        size: extractPageSize(id, params?.groupLimit),
      };

      return {
        queryKey: ["experiments", p],
        queryFn: (context: QueryFunctionContext) =>
          getExperimentsList(context, p),
        refetchInterval,
      };
    }),
  });

  const data = useMemo(() => {
    let sortableBy: string[] | undefined;
    const content = expandedGroups.reduce<GroupedExperiment[]>(
      (acc, group, index) => {
        let experimentsData = experimentsResponse[index].data;
        if (isUndefined(experimentsData)) {
          experimentsData = experimentsCache.current[group.id] ?? {
            content: [],
            total: 0,
          };
        } else {
          experimentsCache.current[group.id] = experimentsData;
        }

        // we are taking sortable data from any experiments that have it defined
        if (!sortableBy && Boolean(experimentsData.sortable_by?.length)) {
          sortableBy = experimentsData.sortable_by;
        }

        const hasMoreData =
          extractPageSize(group.id, params.groupLimit) < experimentsData.total;

        const retVal = experimentsData.content.map((e: Experiment) =>
          wrapExperimentRow(e, group),
        );

        if (hasMoreData) {
          return acc.concat([...retVal, generateMoreRow(group)]);
        }

        return acc.concat(retVal);
      },
      [],
    );

    const groupIds = Object.keys(groupsMap);

    return {
      content,
      groupIds,
      sortable_by: sortableBy ?? [],
      total,
    };
  }, [expandedGroups, experimentsResponse, groupsMap, params.groupLimit]);

  const refetch = useCallback(
    (options: RefetchOptions) => {
      return Promise.all([
        refetchGroups(options),
        ...experimentsResponse.map((r) => r.refetch(options)),
      ]);
    },
    [experimentsResponse, refetchGroups],
  );

  const isPending =
    isGroupsPending ||
    (experimentsResponse.length > 0 &&
      experimentsResponse.some((r) => r.isPending));

  const [isInitialPending, setIsInitialPending] = useState(true);
  useEffect(() => {
    setIsInitialPending((s) => (!isPending && s ? false : s));
  }, [isPending]);

  return {
    data,
    isPending: isInitialPending,
    refetch,
  } as UseGroupedExperimentsListResponse;
}
