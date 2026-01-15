import React, { useCallback } from "react";
import { Plus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Description } from "@/components/ui/description";
import { FormErrorSkeleton } from "@/components/ui/form";
import { cn } from "@/lib/utils";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Filter, FilterRowConfig, Filters } from "@/types/filters";
import { ColumnData, OnChangeFn } from "@/types/shared";
import { createFilter } from "@/lib/filters";
import { generateRandomString } from "@/lib/utils";
import FiltersContent from "@/components/shared/FiltersContent/FiltersContent";

export type FilterValidationError = {
  field?: { message?: string };
  operator?: { message?: string };
  value?: { message?: string };
  key?: { message?: string };
};

type FiltersAccordionSectionProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  config?: { rowsMap: Record<string, FilterRowConfig> };
  filters: Filters;
  onChange: OnChangeFn<Filters>;
  label?: string;
  description?: string;
  className?: string;
  errors?: (FilterValidationError | undefined)[];
  hideBorder?: boolean;
};

const FiltersAccordionSection = <TColumnData,>({
  columns,
  config,
  filters,
  onChange,
  label = "Filters",
  description = "Add filters",
  className = "",
  errors,
  hideBorder = false,
}: FiltersAccordionSectionProps<TColumnData>) => {
  const handleAddFilter = useCallback(() => {
    const newFilter: Filter = {
      ...createFilter(),
      id: generateRandomString(),
    };
    onChange((prev) => [...prev, newFilter]);
  }, [onChange]);

  const hasErrors =
    errors &&
    errors.some((error) => {
      if (!error) return false;
      return (
        error.field?.message ||
        error.operator?.message ||
        error.value?.message ||
        error.key?.message
      );
    });

  return (
    <Accordion type="single" collapsible className={cn("w-full", className)}>
      <AccordionItem value="filters" className={hideBorder ? "" : "border-t"}>
        <AccordionTrigger
          className={cn(
            "h-11 py-1.5 hover:no-underline",
            hasErrors &&
              "text-destructive hover:text-destructive active:text-destructive",
          )}
        >
          {label} {filters.length > 0 && `(${filters.length})`}
        </AccordionTrigger>
        <AccordionContent className="flex flex-col gap-4 px-3 pb-3">
          <Description>{description}</Description>
          <div className="space-y-3">
            {filters.length > 0 && (
              <FiltersContent
                filters={filters}
                setFilters={onChange}
                columns={columns as ColumnData<unknown>[]}
                config={config}
                className="overflow-x-auto py-0"
              />
            )}

            {hasErrors && (
              <div className="space-y-1">
                {errors.map((filterError, index) => {
                  if (!filterError) return null;

                  const errorMessages: string[] = [];

                  if (filterError.field?.message) {
                    errorMessages.push(filterError.field.message);
                  }
                  if (filterError.operator?.message) {
                    errorMessages.push(filterError.operator.message);
                  }
                  if (filterError.value?.message) {
                    errorMessages.push(filterError.value.message);
                  }
                  if (filterError.key?.message) {
                    errorMessages.push(filterError.key.message);
                  }

                  if (errorMessages.length === 0) return null;

                  return (
                    <FormErrorSkeleton key={index}>
                      Filter {index + 1}: {errorMessages.join(", ")}
                    </FormErrorSkeleton>
                  );
                })}
              </div>
            )}

            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleAddFilter}
              className="w-fit"
            >
              <Plus className="mr-1 size-3.5" />
              Add filter
            </Button>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default FiltersAccordionSection;
