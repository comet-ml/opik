import React, { useMemo } from "react";
import { Control, FieldValues, Path, useWatch } from "react-hook-form";
import get from "lodash/get";
import { TrendingUp, TrendingDown } from "lucide-react";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { Description } from "@/components/ui/description";
import {
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { cn } from "@/lib/utils";
import { DropdownOption } from "@/types/shared";
import { DynamicColumn } from "@/types/shared";

interface WidgetRankingSettingsSectionProps<T extends FieldValues> {
  control: Control<T>;
  dynamicScoresColumns: DynamicColumn[];
  onEnableRankingChange: (checked: boolean) => void;
  onRankingMetricChange: (value: string) => void;
  onRankingHigherIsBetterChange: (value: boolean) => void;
}

const WidgetRankingSettingsSection = <T extends FieldValues>({
  control,
  dynamicScoresColumns,
  onEnableRankingChange,
  onRankingMetricChange,
  onRankingHigherIsBetterChange,
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
    <div className="space-y-3">
      <div className="flex items-start justify-between">
        <div className="flex-1 pr-4">
          <div className="flex flex-col gap-0.5 px-0.5">
            <Label className="comet-body-s-accented">Enable ranking</Label>
            <Description>
              Show rank numbers based on primary ranking metric.
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
        <div className="space-y-3 rounded-md border border-border p-3">
          <FormField
            control={control}
            name={"rankingMetric" as Path<T>}
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["rankingMetric"]);

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
                  <FormMessage />
                </FormItem>
              );
            }}
          />
          <FormField
            control={control}
            name={"rankingDirection" as Path<T>}
            render={({ field }) => (
              <FormItem>
                <FormLabel>Ranking order</FormLabel>
                <FormControl>
                  <ToggleGroup
                    type="single"
                    variant="ghost"
                    value={field.value === false ? "lower" : "higher"}
                    onValueChange={(value) => {
                      if (value) {
                        const isHigher = value === "higher";
                        field.onChange(isHigher);
                        onRankingHigherIsBetterChange(isHigher);
                      }
                    }}
                    className="w-fit justify-start"
                  >
                    <ToggleGroupItem
                      value="higher"
                      aria-label="Higher is better"
                      className="gap-1.5"
                    >
                      <TrendingUp className="size-3.5" />
                      <span>Higher is better</span>
                    </ToggleGroupItem>
                    <ToggleGroupItem
                      value="lower"
                      aria-label="Lower is better"
                      className="gap-1.5"
                    >
                      <TrendingDown className="size-3.5" />
                      <span>Lower is better</span>
                    </ToggleGroupItem>
                  </ToggleGroup>
                </FormControl>
              </FormItem>
            )}
          />
        </div>
      )}
    </div>
  );
};

export default WidgetRankingSettingsSection;
