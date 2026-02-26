import { useEffect, useMemo } from "react";
import { JsonParam } from "use-query-params";
import { ColumnSort } from "@tanstack/react-table";

import { Groups } from "@/types/groups";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_PROJECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ExperimentsPathsAutocomplete from "@/components/pages-shared/experiments/ExperimentsPathsAutocomplete/ExperimentsPathsAutocomplete";
import { Filters } from "@/types/filters";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import { usePermissions } from "@/contexts/PermissionsContext";

const DEFAULT_GROUPS: Groups = [];

export type UseExperimentsGroupsAndFiltersProps = {
  storageKeyPrefix: string;
  sortedColumns: ColumnSort[];
  filters: Filters;
  promptId?: string;
  setFilters: (filters: Filters) => void;
};

export const useExperimentsGroupsAndFilters = ({
  storageKeyPrefix,
  sortedColumns,
  filters,
  promptId,
  setFilters,
}: UseExperimentsGroupsAndFiltersProps) => {
  const [groups, setGroups] = useQueryParamAndLocalStorageState<Groups>({
    localStorageKey: `${storageKeyPrefix}-columns-groups`,
    queryKey: `groups`,
    defaultValue: DEFAULT_GROUPS,
    queryParamConfig: JsonParam,
  });

  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  const filterAndGroupColumns: ColumnData<GroupedExperiment>[] = useMemo(
    () => [
      {
        id: COLUMN_PROJECT_ID,
        label: "Project",
        type: COLUMN_TYPE.string,
        disposable: true,
      },
      ...(canViewDatasets
        ? [
            {
              id: COLUMN_DATASET_ID,
              label: "Dataset",
              type: COLUMN_TYPE.string,
              disposable: true,
            },
          ]
        : []),
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
        iconType: "tags",
      },
      {
        id: COLUMN_METADATA_ID,
        label: "Configuration",
        type: COLUMN_TYPE.dictionary,
      },
    ],
    [canViewDatasets],
  );

  const filtersAndGroupsConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_PROJECT_ID]: {
          keyComponent: ProjectsSelectBox,
          keyComponentProps: {
            className: "w-full min-w-72",
          },
          defaultOperator: "=",
          operators: [{ label: "=", value: "=" }],
          sortingMessage: "Last updated at",
        },
        [COLUMN_DATASET_ID]: {
          keyComponent: DatasetSelectBox,
          keyComponentProps: {
            className: "w-full min-w-72",
          },
          defaultOperator: "=",
          operators: [{ label: "=", value: "=" }],
          sortingMessage: "Last experiment created",
        },
        [COLUMN_METADATA_ID]: {
          keyComponent: ExperimentsPathsAutocomplete,
          keyComponentProps: {
            placeholder: "key",
            excludeRoot: true,
            ...(promptId && { promptId }),
            sorting: sortedColumns,
            filters,
          },
        },
      },
    }),
    [filters, sortedColumns, promptId],
  );

  useEffect(() => {
    if (!canViewDatasets) {
      setGroups(groups.filter((g) => g.field !== COLUMN_DATASET_ID));
      setFilters(filters.filter((f) => f.field !== COLUMN_DATASET_ID));
    }
  }, [canViewDatasets, groups, filters, setGroups, setFilters]);

  return {
    groups,
    setGroups,
    filterAndGroupColumns,
    filtersAndGroupsConfig,
  };
};
