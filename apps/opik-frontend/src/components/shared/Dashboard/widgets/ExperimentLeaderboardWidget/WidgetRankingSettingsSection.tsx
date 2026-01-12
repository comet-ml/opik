import React, { useMemo } from "react";
import { Control, FieldValues, Path, useWatch } from "react-hook-form";
import get from "lodash/get";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Description } from "@/components/ui/description";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { cn } from "@/lib/utils";
import { DropdownOption } from "@/types/shared";
import { DynamicColumn } from "@/types/shared";

interface WidgetRankingSettingsSectionProps<T extends FieldValues> {
  control: Control<T>;
  enableRanking: boolean;
  dynamicScoresColumns: DynamicColumn[];
  onEnableRankingChange: (checked: boolean) => void;
  onRankingMetricChange: (value: string) => void;
}

const WidgetRankingSettingsSection = <T extends FieldValues>({
  control,
  enableRanking,
  dynamicScoresColumns,
  onEnableRankingChange,
  onRankingMetricChange,
}: WidgetRankingSettingsSectionProps<T>) => {
  const rankingOptions: DropdownOption<string>[] = useMemo(() => {
    return dynamicScoresColumns.map((col) => ({
      value: col.id,
      label: col.label,
    }));
  }, [dynamicScoresColumns]);

  const currentEnableRanking = useWatch({
    control,
    name: "enableRanking" as Path<T>,
  });

  return (
    <Accordion
      type="single"
      collapsible
      className="border-t"
      defaultValue={enableRanking ? "ranking" : undefined}
    >
      <AccordionItem value="ranking">
        <AccordionTrigger className="h-11 py-1.5">
          Ranking settings
        </AccordionTrigger>
        <AccordionContent className="px-3 pb-3">
          <div className="space-y-3">
            <div className="flex items-start justify-between">
              <div className="flex-1 pr-4">
                <div className="flex flex-col gap-0.5 px-0.5">
                  <Label className="comet-body-s-accented">
                    Enable ranking
                  </Label>
                  <Description>
                    Show rank numbers based on primary ranking metric
                  </Description>
                </div>
              </div>
              <FormField
                control={control}
                name={"enableRanking" as Path<T>}
                render={({ field }) => (
                  <Switch
                    checked={field.value ?? false}
                    onCheckedChange={(checked) => {
                      field.onChange(checked);
                      onEnableRankingChange(checked);
                    }}
                    size="sm"
                  />
                )}
              />
            </div>
            {currentEnableRanking && (
              <div className="rounded-md border border-border p-3">
                <FormField
                  control={control}
                  name={"rankingMetric" as Path<T>}
                  render={({ field, formState }) => {
                    const validationErrors = get(formState.errors, [
                      "rankingMetric",
                    ]);

                    return (
                      <FormItem>
                        <FormLabel>Primary ranking metric</FormLabel>
                        <FormControl>
                          <SelectBox
                            value={field.value || ""}
                            onChange={(value) => {
                              field.onChange(value);
                              onRankingMetricChange(value);
                            }}
                            options={rankingOptions}
                            placeholder="Select ranking metric"
                            className={cn({
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                          />
                        </FormControl>
                        <Description>
                          Select a metric to rank experiments by. Lower values
                          get better ranks.
                        </Description>
                        <FormMessage />
                      </FormItem>
                    );
                  }}
                />
              </div>
            )}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default WidgetRankingSettingsSection;
