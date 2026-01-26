import React, { useMemo, useState, useEffect, useCallback } from "react";
import { Control, useController } from "react-hook-form";
import { Plus, X } from "lucide-react";
import get from "lodash/get";

import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";

import SelectBox from "@/components/shared/SelectBox/SelectBox";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { cn } from "@/lib/utils";
import { BreakdownConfig } from "@/types/dashboard";

import {
  BREAKDOWN_FIELD,
  BREAKDOWN_FIELD_LABELS,
  getCompatibleBreakdownFields,
} from "./breakdown";
import { ProjectMetricsWidgetFormData } from "./schema";

interface ProjectMetricsBreakdownSectionProps {
  control: Control<ProjectMetricsWidgetFormData>;
  metricType: string;
  projectId: string;
  isSpanMetric: boolean;
  breakdown: BreakdownConfig;
  isGroupByDisabledForFeedbackScore: boolean;
  isGroupByDisabledForDuration: boolean;
  isGroupByDisabledForUsage: boolean;
  onBreakdownChange: (breakdown: Partial<BreakdownConfig>) => void;
}

const ProjectMetricsBreakdownSection: React.FC<
  ProjectMetricsBreakdownSectionProps
> = ({
  control,
  metricType,
  projectId,
  isSpanMetric,
  breakdown,
  isGroupByDisabledForFeedbackScore,
  isGroupByDisabledForDuration,
  isGroupByDisabledForUsage,
  onBreakdownChange,
}) => {
  const isMetadataBreakdown = breakdown.field === BREAKDOWN_FIELD.METADATA;
  const hasBreakdownField = breakdown.field !== BREAKDOWN_FIELD.NONE;

  // Combined check for group by disabled state
  const isGroupByDisabled =
    isGroupByDisabledForFeedbackScore ||
    isGroupByDisabledForDuration ||
    isGroupByDisabledForUsage;

  // Local state to track if the group row UI should be shown
  // This allows showing the dropdown without immediately triggering a BE call
  const [showGroupRow, setShowGroupRow] = useState(hasBreakdownField);

  // Get the form field controller for resetting values
  const { field: breakdownFieldController } = useController({
    control,
    name: "breakdown.field",
  });

  const { field: breakdownMetadataKeyController } = useController({
    control,
    name: "breakdown.metadataKey",
  });

  // Sync showGroupRow with actual breakdown state when breakdown changes externally
  useEffect(() => {
    if (hasBreakdownField) {
      setShowGroupRow(true);
    }
  }, [hasBreakdownField]);

  // Get compatible breakdown fields for the current metric type (excluding NONE)
  const compatibleBreakdownFields = useMemo(() => {
    if (!metricType) return [];
    return getCompatibleBreakdownFields(metricType).filter(
      (field) => field !== BREAKDOWN_FIELD.NONE,
    );
  }, [metricType]);

  const breakdownFieldOptions = useMemo(() => {
    return compatibleBreakdownFields.map((field) => ({
      value: field,
      label: BREAKDOWN_FIELD_LABELS[field],
    }));
  }, [compatibleBreakdownFields]);

  const handleAddGroup = useCallback(() => {
    // Only show the group row UI without setting a breakdown field
    // The preview won't update until a field is actually selected
    setShowGroupRow(true);
  }, []);

  const handleRemoveGroup = useCallback(() => {
    setShowGroupRow(false);
    // Reset the form field values so they don't retain the previous selection
    breakdownFieldController.onChange(BREAKDOWN_FIELD.NONE);
    breakdownMetadataKeyController.onChange(undefined);
    onBreakdownChange({
      field: BREAKDOWN_FIELD.NONE,
      metadataKey: undefined,
    });
  }, [
    breakdownFieldController,
    breakdownMetadataKeyController,
    onBreakdownChange,
  ]);

  // Effect to reset when group by is disabled
  useEffect(() => {
    if (isGroupByDisabled && showGroupRow) {
      setShowGroupRow(false);
    }
  }, [isGroupByDisabled, showGroupRow]);

  return (
    <Accordion type="single" collapsible className="w-full">
      <AccordionItem value="groupby" className="">
        <AccordionTrigger className="h-11 py-1.5 hover:no-underline">
          Group by {hasBreakdownField && "(1)"}
        </AccordionTrigger>
        <AccordionContent className="flex flex-col gap-4 px-3 pb-3">
          <Description>Add groups to aggregate data.</Description>
          <div className="space-y-3">
            {isGroupByDisabled ? (
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled
                  className="w-fit"
                >
                  <Plus className="mr-1 size-3.5" />
                  Add group
                </Button>
                <ExplainerIcon
                  {...EXPLAINERS_MAP[
                    isGroupByDisabledForDuration
                      ? EXPLAINER_ID.duration_groupby_requires_single_metric
                      : isGroupByDisabledForUsage
                        ? EXPLAINER_ID.usage_groupby_requires_single_metric
                        : EXPLAINER_ID.feedback_score_groupby_requires_single_metric
                  ]}
                />
              </div>
            ) : showGroupRow ? (
              <>
                {/* Group row with field selector and remove button */}
                <div className="flex items-start gap-2">
                  <span className="comet-body-s flex h-8 items-center pr-2">
                    By
                  </span>
                  <FormField
                    control={control}
                    name="breakdown.field"
                    render={({ field, formState }) => {
                      const validationErrors = get(formState.errors, [
                        "breakdown",
                        "field",
                      ]);
                      return (
                        <FormItem className="min-w-40">
                          <FormControl>
                            <SelectBox
                              className={cn({
                                "border-destructive": Boolean(
                                  validationErrors?.message,
                                ),
                              })}
                              value={
                                field.value === BREAKDOWN_FIELD.NONE
                                  ? ""
                                  : field.value || ""
                              }
                              onChange={(value) => {
                                field.onChange(value);
                                onBreakdownChange({
                                  field: value as BREAKDOWN_FIELD,
                                  metadataKey:
                                    value === BREAKDOWN_FIELD.METADATA
                                      ? breakdown.metadataKey
                                      : undefined,
                                });
                              }}
                              options={breakdownFieldOptions}
                              placeholder="Select field"
                            />
                          </FormControl>
                          <FormMessage />
                        </FormItem>
                      );
                    }}
                  />
                  {/* Configuration key input when Configuration field is selected */}
                  {isMetadataBreakdown && (
                    <FormField
                      control={control}
                      name="breakdown.metadataKey"
                      render={({ field, formState }) => {
                        const validationErrors = get(formState.errors, [
                          "breakdown",
                          "metadataKey",
                        ]);
                        return (
                          <FormItem className="min-w-32 max-w-[30vw] flex-1">
                            <FormControl>
                              <TracesOrSpansPathsAutocomplete
                                hasError={Boolean(validationErrors?.message)}
                                rootKeys={["metadata"]}
                                projectId={projectId}
                                type={
                                  isSpanMetric
                                    ? TRACE_DATA_TYPE.spans
                                    : TRACE_DATA_TYPE.traces
                                }
                                placeholder="key"
                                excludeRoot={true}
                                value={field.value || ""}
                                onValueChange={(value) => {
                                  field.onChange(value);
                                  onBreakdownChange({
                                    metadataKey: value,
                                  });
                                }}
                              />
                            </FormControl>
                            <FormMessage />
                          </FormItem>
                        );
                      }}
                    />
                  )}
                  <Button
                    type="button"
                    variant="minimal"
                    size="icon-xs"
                    onClick={handleRemoveGroup}
                    className="mt-1.5"
                  >
                    <X className="size-4" />
                  </Button>
                </div>
              </>
            ) : (
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
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default ProjectMetricsBreakdownSection;
