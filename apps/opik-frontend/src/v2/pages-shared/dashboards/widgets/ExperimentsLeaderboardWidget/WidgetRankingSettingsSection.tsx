import React, { useMemo } from "react";
import { Control, FieldValues, Path, useWatch } from "react-hook-form";
import get from "lodash/get";
import { TrendingUp, TrendingDown } from "lucide-react";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { cn } from "@/lib/utils";
import { DropdownOption } from "@/types/shared";
import { DynamicColumn } from "@/types/shared";

interface WidgetRankingSettingsSectionProps<T extends FieldValues> {
  control: Control<T>;
  dynamicScoresColumns: DynamicColumn[];
  onRankingMetricChange: (value: string) => void;
  onRankingHigherIsBetterChange: (value: boolean) => void;
}

export const NO_RANKING_VALUE = "__no_ranking__";

const WidgetRankingSettingsSection = <T extends FieldValues>({
  control,
  dynamicScoresColumns,
  onRankingMetricChange,
  onRankingHigherIsBetterChange,
}: WidgetRankingSettingsSectionProps<T>) => {
  const rankingOptions: DropdownOption<string>[] = useMemo(() => {
    return [
      { value: NO_RANKING_VALUE, label: "No ranking" },
      ...dynamicScoresColumns.map((col) => ({
        value: col.id,
        label: col.label,
      })),
    ];
  }, [dynamicScoresColumns]);

  const currentRankingMetric = useWatch({
    control,
    name: "rankingMetric" as Path<T>,
  });

  const isRankingEnabled =
    Boolean(currentRankingMetric) && currentRankingMetric !== NO_RANKING_VALUE;

  return (
    <div className="flex items-end gap-4">
      <FormField
        control={control}
        name={"rankingMetric" as Path<T>}
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["rankingMetric"]);

          return (
            <FormItem className="flex-1">
              <FormLabel>Ranking metric</FormLabel>
              <FormControl>
                <SelectBox
                  value={field.value || NO_RANKING_VALUE}
                  onChange={onRankingMetricChange}
                  options={rankingOptions}
                  placeholder="Select ranking metric"
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />
      <FormField
        control={control}
        name={"rankingDirection" as Path<T>}
        render={({ field }) => (
          <FormItem className="shrink-0">
            <FormLabel>Ranking order</FormLabel>
            <FormControl>
              <ToggleGroup
                type="single"
                variant="secondary"
                value={field.value === false ? "lower" : "higher"}
                onValueChange={(value) => {
                  if (value) {
                    const isHigher = value === "higher";
                    field.onChange(isHigher);
                    onRankingHigherIsBetterChange(isHigher);
                  }
                }}
                className="w-fit justify-start"
                disabled={!isRankingEnabled}
              >
                <ToggleGroupItem
                  value="higher"
                  aria-label="High first"
                  className="gap-1.5 whitespace-nowrap"
                >
                  <TrendingUp className="size-3.5" />
                  <span>High first</span>
                </ToggleGroupItem>
                <ToggleGroupItem
                  value="lower"
                  aria-label="Low first"
                  className="gap-1.5 whitespace-nowrap"
                >
                  <TrendingDown className="size-3.5" />
                  <span>Low first</span>
                </ToggleGroupItem>
              </ToggleGroup>
            </FormControl>
          </FormItem>
        )}
      />
    </div>
  );
};

export default WidgetRankingSettingsSection;
