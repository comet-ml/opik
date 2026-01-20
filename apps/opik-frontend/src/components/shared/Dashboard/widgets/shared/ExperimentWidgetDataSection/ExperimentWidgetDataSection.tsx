import React, { useCallback, useMemo } from "react";
import {
  Control,
  FieldPath,
  FieldValues,
  useController,
  useFormState,
} from "react-hook-form";
import isArray from "lodash/isArray";
import isFunction from "lodash/isFunction";

import { cn } from "@/lib/utils";
import { FilterOperator, Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import FiltersAccordionSection from "@/components/shared/FiltersAccordionSection/FiltersAccordionSection";
import GroupsAccordionSection, {
  GroupValidationError,
} from "@/components/shared/GroupsAccordionSection/GroupsAccordionSection";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import ExperimentsPathsAutocomplete from "@/components/pages-shared/experiments/ExperimentsPathsAutocomplete/ExperimentsPathsAutocomplete";
import { Description } from "@/components/ui/description";

type ExperimentColumnData = {
  id: string;
  dataset_id?: string;
};

const EXPERIMENT_DATA_COLUMNS: ColumnData<ExperimentColumnData>[] = [
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

interface ExperimentWidgetDataSectionProps<T extends FieldValues> {
  control: Control<T>;
  filtersFieldName: FieldPath<T>;
  groupsFieldName?: FieldPath<T> | "";
  filters: Filters;
  groups?: Groups;
  onFiltersChange?: (filters: Filters) => void;
  onGroupsChange?: (groups: Groups) => void;
  className?: string;
}

const ExperimentWidgetDataSection = <T extends FieldValues>({
  control,
  filtersFieldName,
  groupsFieldName,
  filters,
  groups,
  onFiltersChange,
  onGroupsChange,
  className = "",
}: ExperimentWidgetDataSectionProps<T>) => {
  const { field: filtersField } = useController({
    control,
    name: filtersFieldName,
  });

  const { field: groupsField } = useController({
    control,
    name: (groupsFieldName || "groups") as FieldPath<T>,
  });

  const dataConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_DATASET_ID]: {
          keyComponent: DatasetSelectBox as React.FunctionComponent<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            className: "w-full min-w-72",
          },
          defaultOperator: "=" as FilterOperator,
          operators: [{ label: "=", value: "=" as FilterOperator }],
          sortingMessage: "Last experiment created",
        },
        [COLUMN_METADATA_ID]: {
          keyComponent:
            ExperimentsPathsAutocomplete as React.FunctionComponent<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            placeholder: "key",
            excludeRoot: true,
          },
        },
      },
    }),
    [],
  );

  const setFilters = useCallback(
    (filtersOrUpdater: Filters | ((prev: Filters) => Filters)) => {
      let updatedFilters: Filters;

      if (isFunction(filtersOrUpdater)) {
        const currentFilters = (filtersField.value as Filters) || [];
        updatedFilters = filtersOrUpdater(currentFilters);
      } else {
        updatedFilters = filtersOrUpdater;
      }

      filtersField.onChange(updatedFilters);
      onFiltersChange?.(updatedFilters);
    },
    [filtersField, onFiltersChange],
  );

  const setGroups = useCallback(
    (groupsOrUpdater: Groups | ((prev: Groups) => Groups)) => {
      if (!groupsFieldName) return;

      let updatedGroups: Groups;

      if (isFunction(groupsOrUpdater)) {
        const currentGroups = (groupsField.value as Groups) || [];
        updatedGroups = groupsOrUpdater(currentGroups);
      } else {
        updatedGroups = groupsOrUpdater;
      }

      groupsField.onChange(updatedGroups);
      onGroupsChange?.(updatedGroups);
    },
    [groupsFieldName, groupsField, onGroupsChange],
  );

  const { errors: formErrors } = useFormState({ control });
  const filterErrors = formErrors[filtersFieldName];
  const parsedFilterErrors =
    filterErrors && isArray(filterErrors)
      ? (filterErrors as unknown[]).map((e) =>
          e
            ? (e as {
                field?: { message?: string };
                operator?: { message?: string };
                value?: { message?: string };
                key?: { message?: string };
              })
            : undefined,
        )
      : undefined;

  const groupErrors = groupsFieldName ? formErrors[groupsFieldName] : undefined;
  const parsedGroupErrors =
    groupErrors && isArray(groupErrors)
      ? (groupErrors as unknown[]).map((e) =>
          e ? (e as GroupValidationError) : undefined,
        )
      : undefined;

  return (
    <div className={cn("flex flex-col", className)}>
      <Description className="mb-4">
        {groupsFieldName
          ? "Add filters to focus on specific experiments and group them by configuration to aggregate feedback scores."
          : "Add filters to focus on specific experiments."}
      </Description>

      <FiltersAccordionSection
        columns={EXPERIMENT_DATA_COLUMNS as ColumnData<unknown>[]}
        config={dataConfig}
        filters={filters}
        onChange={setFilters}
        label="Filters"
        errors={parsedFilterErrors}
      />

      {groupsFieldName && (
        <GroupsAccordionSection
          columns={EXPERIMENT_DATA_COLUMNS as ColumnData<unknown>[]}
          config={dataConfig}
          groups={groups || []}
          onChange={setGroups}
          label="Group by"
          errors={parsedGroupErrors}
          className="w-full"
          hideSorting
          hideBorder
        />
      )}
    </div>
  );
};

export default ExperimentWidgetDataSection;
