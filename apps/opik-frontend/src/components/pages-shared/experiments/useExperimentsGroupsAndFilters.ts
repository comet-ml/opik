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

export const FILTER_AND_GROUP_COLUMNS: ColumnData<GroupedExperiment>[] = [
  {
    id: COLUMN_DATASET_ID,
    label: "Dataset",
    type: COLUMN_TYPE.string,
    disposable: true,
  },
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
];

const DEFAULT_GROUPS: Groups = [];

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

  return {
    groups,
    setGroups,
    filtersAndGroupsConfig,
  };
};
