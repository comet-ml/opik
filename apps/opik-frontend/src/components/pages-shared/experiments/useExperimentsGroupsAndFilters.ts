import { useMemo } from "react";
import { JsonParam } from "use-query-params";
import { ColumnSort } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";

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

export const useFilterAndGroupColumns = () => {
  const { t, i18n } = useTranslation();
  
  return useMemo<ColumnData<GroupedExperiment>[]>(() => [
    {
      id: COLUMN_DATASET_ID,
      label: t("experiments.columns.dataset"),
      type: COLUMN_TYPE.string,
      disposable: true,
    },
    {
      id: COLUMN_METADATA_ID,
      label: t("configuration.title"),
      type: COLUMN_TYPE.dictionary,
    },
  ], [t, i18n.language]);
};

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
  const { t } = useTranslation();
  
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
          operators: [{ label: t("filters.operators.equals"), value: "=" }],
          sortingMessage: t("experiments.sortingMessage.lastExperimentCreated"),
        },
        [COLUMN_METADATA_ID]: {
          keyComponent: ExperimentsPathsAutocomplete,
          keyComponentProps: {
            placeholder: t("filters.key"),
            excludeRoot: true,
            ...(promptId && { promptId }),
            sorting: sortedColumns,
            filters,
          },
        },
      },
    }),
    [filters, sortedColumns, promptId, t],
  );

  return {
    groups,
    setGroups,
    filtersAndGroupsConfig,
  };
};
