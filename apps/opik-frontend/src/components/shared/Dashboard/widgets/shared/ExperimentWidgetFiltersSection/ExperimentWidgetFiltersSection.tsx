import React, { useCallback, useMemo } from "react";
import {
  Control,
  FieldPath,
  FieldValues,
  useController,
  useFormState,
} from "react-hook-form";
import { Plus } from "lucide-react";
import isArray from "lodash/isArray";

import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Description } from "@/components/ui/description";
import { FormErrorSkeleton } from "@/components/ui/form";
import { cn } from "@/lib/utils";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { FilterOperator, Filters } from "@/types/filters";
import { Groups, Group } from "@/types/groups";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { generateRandomString } from "@/lib/utils";
import GroupsContent from "@/components/shared/GroupsContent/GroupsContent";
import FiltersAccordionSection from "@/components/shared/FiltersAccordionSection/FiltersAccordionSection";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import ExperimentsPathsAutocomplete from "@/components/pages-shared/experiments/ExperimentsPathsAutocomplete/ExperimentsPathsAutocomplete";

type ExperimentColumnData = {
  id: string;
  dataset_id?: string;
};

const FILTER_AND_GROUP_COLUMNS: ColumnData<ExperimentColumnData>[] = [
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

interface ExperimentWidgetFiltersSectionProps<T extends FieldValues> {
  control: Control<T>;
  filtersFieldName: FieldPath<T>;
  groupsFieldName: FieldPath<T>;
  filters: Filters;
  groups: Groups;
  onFiltersChange?: (filters: Filters) => void;
  onGroupsChange?: (groups: Groups) => void;
  className?: string;
}

const ExperimentWidgetFiltersSection = <T extends FieldValues>({
  control,
  filtersFieldName,
  groupsFieldName,
  filters,
  groups,
  onFiltersChange,
  onGroupsChange,
  className = "",
}: ExperimentWidgetFiltersSectionProps<T>) => {
  const { field: filtersField } = useController({
    control,
    name: filtersFieldName,
  });

  const { field: groupsField } = useController({
    control,
    name: groupsFieldName,
  });

  const filtersAndGroupsConfig = useMemo(
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
            filters,
          },
        },
      },
    }),
    [filters],
  );

  const setFilters = useCallback(
    (filtersOrUpdater: Filters | ((prev: Filters) => Filters)) => {
      let updatedFilters: Filters;

      if (typeof filtersOrUpdater === "function") {
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
      let updatedGroups: Groups;

      if (typeof groupsOrUpdater === "function") {
        const currentGroups = (groupsField.value as Groups) || [];
        updatedGroups = groupsOrUpdater(currentGroups);
      } else {
        updatedGroups = groupsOrUpdater;
      }

      const limitedGroups = updatedGroups.slice(0, 1);
      groupsField.onChange(limitedGroups);
      onGroupsChange?.(limitedGroups);
    },
    [groupsField, onGroupsChange],
  );

  const handleAddGroup = useCallback(() => {
    if (groups.length >= 1) return;
    const newGroup: Group = {
      id: generateRandomString(),
      field: "",
      key: "",
      direction: "" as Group["direction"],
      type: "",
    };
    setGroups((prev) => [...prev, newGroup]);
  }, [groups.length, setGroups]);

  const { errors: formErrors } = useFormState({ control });
  const filterErrors = formErrors[filtersFieldName];
  const errors =
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

  const groupErrors = formErrors[groupsFieldName];
  const hasGroupErrors = groupErrors && isArray(groupErrors);

  return (
    <div className={cn("flex flex-col gap-4", className)}>
      <FiltersAccordionSection
        columns={FILTER_AND_GROUP_COLUMNS as ColumnData<unknown>[]}
        config={filtersAndGroupsConfig}
        filters={filters}
        onChange={setFilters}
        label="Filters"
        description="Add filters to focus the widget on specific experiments."
        errors={errors}
      />

      <Accordion type="single" collapsible className="w-full">
        <AccordionItem value="groups" className="border-t">
          <AccordionTrigger className="py-3 hover:no-underline">
            <Label className="text-sm font-medium">
              Group by {groups.length > 0 && `(${groups.length})`}
            </Label>
          </AccordionTrigger>
          <AccordionContent className="flex flex-col gap-4 px-3 pb-3">
            <Description>
              Group experiments by configuration to aggregate feedback scores.
            </Description>
            <div className="space-y-3">
              {groups.length > 0 && (
                <GroupsContent
                  groups={groups}
                  setGroups={setGroups}
                  columns={FILTER_AND_GROUP_COLUMNS as ColumnData<unknown>[]}
                  config={filtersAndGroupsConfig}
                  className="py-0"
                />
              )}

              {hasGroupErrors && (groupErrors as unknown[]).length > 0 && (
                <div className="space-y-1">
                  {(groupErrors as unknown[]).map((groupError, index) => {
                    if (!groupError) return null;

                    const errorMessages: string[] = [];
                    const error = groupError as Record<
                      string,
                      { message?: string }
                    >;

                    if (error.field?.message) {
                      errorMessages.push(error.field.message);
                    }
                    if (error.key?.message) {
                      errorMessages.push(error.key.message);
                    }

                    if (errorMessages.length === 0) return null;

                    return (
                      <FormErrorSkeleton key={index}>
                        Group {index + 1}: {errorMessages.join(", ")}
                      </FormErrorSkeleton>
                    );
                  })}
                </div>
              )}

              {groups.length < 1 && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={handleAddGroup}
                  className="w-fit"
                >
                  <Plus className="mr-1 size-3.5" />
                  Add group
                </Button>
              )}

              {groups.length >= 1 && (
                <Description className="text-muted-foreground">
                  Only one level of grouping is supported.
                </Description>
              )}
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default ExperimentWidgetFiltersSection;
