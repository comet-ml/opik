import { useMemo } from "react";
import { JsonParam } from "use-query-params";
import { ColumnSort } from "@tanstack/react-table";

import { Groups } from "@/types/groups";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import ExperimentsPathsAutocomplete from "@/components/pages-shared/experiments/ExperimentsPathsAutocomplete/ExperimentsPathsAutocomplete";
import { Filters } from "@/types/filters";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import { SORT_DIRECTION } from "@/types/sorting";

export const FILTER_AND_GROUP_COLUMNS: ColumnData<GroupedExperiment>[] = [
  {
    id: COLUMN_DATASET_ID,
    label: "Dataset",
    type: COLUMN_TYPE.string,
    disposable: true,
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Configuration",
    type: COLUMN_TYPE.dictionary,
  },
];

const DEFAULT_GROUPS: Groups = [
  {
    id: "default_groups",
    field: "dataset_id",
    type: COLUMN_TYPE.string,
    direction: SORT_DIRECTION.ASC,
    key: "",
  },
];

export type UseExperimentsGroupsAndFiltersProps = {
  storageKeyPrefix: string;
  sortedColumns: ColumnSort[];
  filters: Filters;
  promptId?: string;
};

export const useExperimentsGroupsAndFilters = ({
  storageKeyPrefix,
  sortedColumns,
  filters,
  promptId,
}: UseExperimentsGroupsAndFiltersProps) => {
  const [groups, setGroups] = useQueryParamAndLocalStorageState<Groups>({
    localStorageKey: `${storageKeyPrefix}-columns-groups`,
    queryKey: `groups`,
    defaultValue: DEFAULT_GROUPS,
    queryParamConfig: JsonParam,
  });

  const filtersAndGroupsConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_DATASET_ID]: {
          keyComponent: DatasetSelectBox,
          keyComponentProps: {
            className: "w-full min-w-72",
          },
          defaultOperator: "=",
          operators: [{ label: "=", value: "=" }],
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
          defaultOperator: "=",
          operators: [
            { label: "=", value: "=" },
            { label: "contains", value: "contains" },
            { label: "doesn't contain", value: "not_contains" },
            { label: "starts with", value: "starts_with" },
            { label: "ends with", value: "ends_with" },
            { label: ">", value: ">" },
            { label: "<", value: "<" },
          ],
        },
      },
    }),
    [filters, sortedColumns, promptId],
  );

  return {
    groups,
    setGroups,
    filtersAndGroupsConfig,
  };
};
