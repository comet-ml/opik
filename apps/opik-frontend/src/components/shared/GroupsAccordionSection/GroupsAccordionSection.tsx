import React, { useCallback } from "react";
import { Plus } from "lucide-react";

import { Button } from "@/components/ui/button";
import { Description } from "@/components/ui/description";
import { FormErrorSkeleton } from "@/components/ui/form";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Groups, Group, GroupRowConfig } from "@/types/groups";
import { MAX_GROUP_LEVELS } from "@/constants/groups";
import { ColumnData } from "@/types/shared";
import { cn, generateRandomString } from "@/lib/utils";
import GroupsContent from "@/components/shared/GroupsContent/GroupsContent";

export type GroupValidationError = {
  field?: { message?: string };
  key?: { message?: string };
};

type GroupsAccordionSectionConfig = {
  rowsMap: Record<string, GroupRowConfig>;
};

type GroupsAccordionSectionProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  config?: GroupsAccordionSectionConfig;
  groups: Groups;
  onChange: (groups: Groups | ((prev: Groups) => Groups)) => void;
  label?: string;
  description?: string;
  className?: string;
  errors?: (GroupValidationError | undefined)[];
  hideSorting?: boolean;
  hideBorder?: boolean;
};

const GroupsAccordionSection = <TColumnData,>({
  columns,
  config,
  groups,
  onChange,
  label = "Group by",
  description = "Add groups to aggregate data.",
  className = "",
  errors,
  hideSorting = false,
  hideBorder = false,
}: GroupsAccordionSectionProps<TColumnData>) => {
  const handleAddGroup = useCallback(() => {
    if (groups.length >= MAX_GROUP_LEVELS) return;
    const newGroup: Group = {
      id: generateRandomString(),
      field: "",
      key: "",
      direction: "" as Group["direction"],
      type: "",
    };
    onChange((prev) => [...prev, newGroup]);
  }, [groups.length, onChange]);

  const hasErrors =
    errors &&
    errors.some((error) => {
      if (!error) return false;
      return error.field?.message || error.key?.message;
    });

  return (
    <Accordion type="single" collapsible className={className}>
      <AccordionItem value="groups" className={hideBorder ? "" : "border-t"}>
        <AccordionTrigger
          className={cn(
            "h-11 py-1.5 hover:no-underline",
            hasErrors &&
              "text-destructive hover:text-destructive active:text-destructive",
          )}
        >
          {label} {groups.length > 0 && `(${groups.length})`}
        </AccordionTrigger>
        <AccordionContent className="flex flex-col gap-4 px-3 pb-3">
          <Description>{description}</Description>
          <div className="space-y-3">
            {groups.length > 0 && (
              <GroupsContent
                groups={groups}
                setGroups={onChange}
                columns={columns as ColumnData<unknown>[]}
                config={config}
                className="py-0"
                hideSorting={hideSorting}
              />
            )}

            {hasErrors && (
              <div className="space-y-1">
                {errors.map((groupError, index) => {
                  if (!groupError) return null;

                  const errorMessages: string[] = [];

                  if (groupError.field?.message) {
                    errorMessages.push(groupError.field.message);
                  }
                  if (groupError.key?.message) {
                    errorMessages.push(groupError.key.message);
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

            <div className="flex flex-col gap-2">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleAddGroup}
                disabled={groups.length >= MAX_GROUP_LEVELS}
                className="w-fit"
              >
                <Plus className="mr-1 size-3.5" />
                Add group
              </Button>

              {groups.length >= MAX_GROUP_LEVELS && (
                <Description className="text-muted-foreground">
                  Maximum {MAX_GROUP_LEVELS} levels reached
                </Description>
              )}
            </div>
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default GroupsAccordionSection;
