import React, {
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { get, isObject, isArray } from "lodash";
import { cn } from "@/lib/utils";

import {
  AddWidgetConfig,
  StatCardWidget,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Description } from "@/components/ui/description";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import { useDashboardStore } from "@/store/DashboardStore";
import { ProjectDashboardConfig } from "@/types/dashboard";
import ProjectsSelectBox from "@/components/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/components/shared/Dashboard/widgets/shared/ProjectWidgetFiltersSection";
import { StatCardWidgetSchema, StatCardWidgetFormData } from "./schema";
import useTracesStatistic from "@/api/traces/useTracesStatistic";
import useSpansStatistic from "@/api/traces/useSpansStatistic";
import { ColumnStatistic, STATISTIC_AGGREGATION_TYPE } from "@/types/shared";
import isNumber from "lodash/isNumber";

type StatCardEditorProps = AddWidgetConfig & {
  onChange: (data: Partial<AddWidgetConfig>) => void;
  onValidationChange?: (isValid: boolean) => void;
};

const SOURCE_OPTIONS = [
  { value: "traces", label: "Traces stats" },
  { value: "spans", label: "Spans stats" },
];

const formatMetricOptionLabel = (
  statName: string,
  statType: STATISTIC_AGGREGATION_TYPE,
): string => {
  const nameLabel = statName
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");

  const typeLabel =
    statType === STATISTIC_AGGREGATION_TYPE.AVG
      ? "Average"
      : statType === STATISTIC_AGGREGATION_TYPE.COUNT
        ? "Total"
        : "";

  return typeLabel ? `${typeLabel} ${nameLabel}` : nameLabel;
};

const StatCardEditor = forwardRef<WidgetEditorHandle, StatCardEditorProps>(
  ({ title, subtitle, config, onChange }, ref) => {
    const widgetConfig = config as StatCardWidget["config"];
    const source = widgetConfig?.source || "traces";
    const metric = widgetConfig?.metric || "";
    const localProjectId = widgetConfig?.projectId;

    const traceFilters = useMemo(
      () => widgetConfig?.traceFilters || [],
      [widgetConfig?.traceFilters],
    );
    const spanFilters = useMemo(
      () => widgetConfig?.spanFilters || [],
      [widgetConfig?.spanFilters],
    );

    const projectConfig = useDashboardStore(
      (state) => state.config as ProjectDashboardConfig | null,
    );
    const projectId = localProjectId || projectConfig?.projectId || "";
    const isTraceSource = source === "traces";
    const isSpanSource = source === "spans";

    const tracesStatistic = useTracesStatistic(
      {
        projectId,
      },
      {
        enabled: isTraceSource && !!projectId && projectId.length > 0,
      },
    );

    const spansStatistic = useSpansStatistic(
      {
        projectId: projectId,
      },
      {
        enabled: isSpanSource && !!projectId && projectId.length > 0,
      },
    );

    const metricOptions = useMemo(() => {
      const statsData = isTraceSource
        ? tracesStatistic.data?.stats
        : spansStatistic.data?.stats;

      if (!statsData || statsData.length === 0) {
        return [];
      }

      const options: Array<{ value: string; label: string }> = [];

      statsData.forEach((stat: ColumnStatistic) => {
        if (stat.type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE) {
          options.push({
            value: `${stat.name}.p50`,
            label: `${formatMetricOptionLabel(stat.name, stat.type)} (P50)`,
          });
          options.push({
            value: `${stat.name}.p90`,
            label: `${formatMetricOptionLabel(stat.name, stat.type)} (P90)`,
          });
          options.push({
            value: `${stat.name}.p99`,
            label: `${formatMetricOptionLabel(stat.name, stat.type)} (P99)`,
          });
        } else {
          const statValue = stat.value;

          if (isObject(statValue) && !isArray(statValue)) {
            Object.keys(statValue as Record<string, unknown>).forEach((key) => {
              const nestedValue = get(statValue, key);
              if (isNumber(nestedValue) || nestedValue !== null) {
                const keyLabel = key
                  .split("_")
                  .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
                  .join(" ");
                options.push({
                  value: `${stat.name}.${key}`,
                  label: `${formatMetricOptionLabel(
                    stat.name,
                    stat.type,
                  )}.${keyLabel}`,
                });
              }
            });
          } else {
            options.push({
              value: stat.name,
              label: formatMetricOptionLabel(stat.name, stat.type),
            });
          }
        }
      });

      return options;
    }, [
      isTraceSource,
      tracesStatistic.data?.stats,
      spansStatistic.data?.stats,
    ]);

    const form = useForm<StatCardWidgetFormData>({
      resolver: zodResolver(StatCardWidgetSchema),
      mode: "onTouched",
      defaultValues: {
        title,
        subtitle: subtitle || "",
        source,
        metric,
        projectId: localProjectId,
        traceFilters,
        spanFilters,
      },
    });

    const currentFilters = isTraceSource
      ? form.watch("traceFilters") || []
      : form.watch("spanFilters") || [];

    useEffect(() => {
      if (isTraceSource && form.formState.errors.traceFilters) {
        form.clearErrors("traceFilters");
      }
      if (isSpanSource && form.formState.errors.spanFilters) {
        form.clearErrors("spanFilters");
      }
    }, [currentFilters.length, form, isTraceSource, isSpanSource]);

    useImperativeHandle(ref, () => ({
      submit: async () => {
        const isValid = await form.trigger();
        if (isValid) {
          const values = form.getValues();
          onChange({
            title: values.title,
            subtitle: values.subtitle,
            config: {
              ...config,
              source: values.source,
              projectId: values.projectId,
              metric: values.metric,
              traceFilters: values.traceFilters,
              spanFilters: values.spanFilters,
            },
          });
        }
        return isValid;
      },
      isValid: form.formState.isValid,
    }));

    useEffect(() => {
      form.reset({
        title,
        subtitle: subtitle || "",
        source,
        metric,
        projectId: localProjectId,
        traceFilters,
        spanFilters,
      });
    }, [
      title,
      subtitle,
      source,
      metric,
      localProjectId,
      traceFilters,
      spanFilters,
      form,
    ]);

    const handleTitleChange = (value: string) => {
      onChange({ title: value });
    };

    const handleSubtitleChange = (value: string) => {
      onChange({ subtitle: value });
    };

    const handleSourceChange = (value: string) => {
      onChange({
        config: {
          ...config,
          source: value as "traces" | "spans",
        },
      });
    };

    const handleProjectChange = (projectId: string) => {
      onChange({
        config: {
          ...config,
          projectId,
        },
      });
    };

    const handleMetricChange = (value: string) => {
      onChange({
        config: {
          ...config,
          metric: value,
        },
      });
    };

    return (
      <Form {...form}>
        <div className="space-y-4">
          <FormField
            control={form.control}
            name="title"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["title"]);
              return (
                <FormItem>
                  <FormLabel>Widget title</FormLabel>
                  <FormControl>
                    <Input
                      className={cn({
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                      placeholder="Enter widget title"
                      {...field}
                      onChange={(e) => {
                        field.onChange(e);
                        handleTitleChange(e.target.value);
                      }}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          <FormField
            control={form.control}
            name="subtitle"
            render={({ field }) => (
              <FormItem>
                <FormLabel>Widget subtitle (optional)</FormLabel>
                <FormControl>
                  <Input
                    placeholder="Enter widget subtitle"
                    {...field}
                    onChange={(e) => {
                      field.onChange(e);
                      handleSubtitleChange(e.target.value);
                    }}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            )}
          />

          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1">
              <h3 className="comet-body-accented">Data source</h3>
              <Description>
                Choose where this widget pulls its data from.
              </Description>
            </div>

            <FormField
              control={form.control}
              name="source"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, ["source"]);
                return (
                  <FormItem>
                    <FormLabel>Stats source</FormLabel>
                    <FormControl>
                      <SelectBox
                        className={cn({
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                        value={field.value}
                        onChange={(value) => {
                          field.onChange(value);
                          handleSourceChange(value);
                        }}
                        options={SOURCE_OPTIONS}
                        placeholder="Select source"
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />

            <FormField
              control={form.control}
              name="projectId"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, ["projectId"]);
                return (
                  <FormItem>
                    <FormLabel>Project</FormLabel>
                    <Description>
                      Pick the project that contains the data you want to
                      visualize.
                    </Description>
                    <FormControl>
                      <ProjectsSelectBox
                        className={cn({
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                        value={field.value || ""}
                        onValueChange={(value) => {
                          field.onChange(value);
                          handleProjectChange(value);
                        }}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />

            <FormField
              control={form.control}
              name="metric"
              render={({ field, formState }) => {
                const validationErrors = get(formState.errors, ["metric"]);
                const isLoading =
                  (isTraceSource && tracesStatistic.isLoading) ||
                  (isSpanSource && spansStatistic.isLoading);
                const hasError =
                  (isTraceSource && tracesStatistic.error) ||
                  (isSpanSource && spansStatistic.error);
                const placeholder = isLoading
                  ? "Loading available metrics..."
                  : hasError
                    ? "Error loading metrics"
                    : !projectId
                      ? "Select a project first"
                      : metricOptions.length === 0
                        ? "No metrics available"
                        : "Select a metric";

                return (
                  <FormItem>
                    <FormLabel>Metric</FormLabel>
                    <Description>
                      Select the metric you want this widget to display.
                    </Description>
                    <FormControl>
                      <SelectBox
                        className={cn({
                          "border-destructive": Boolean(
                            validationErrors?.message,
                          ),
                        })}
                        value={field.value}
                        onChange={(value) => {
                          field.onChange(value);
                          handleMetricChange(value);
                        }}
                        options={metricOptions}
                        placeholder={placeholder}
                        disabled={isLoading || !projectId}
                      />
                    </FormControl>
                    <FormMessage />
                  </FormItem>
                );
              }}
            />

            {source && (isTraceSource || isSpanSource) && (
              <ProjectWidgetFiltersSection
                control={form.control}
                fieldName={isTraceSource ? "traceFilters" : "spanFilters"}
                projectId={projectId}
                filterType={isTraceSource ? "trace" : "span"}
                onFiltersChange={(filters) => {
                  onChange({
                    config: {
                      ...config,
                      ...(isTraceSource
                        ? { traceFilters: filters }
                        : { spanFilters: filters }),
                    },
                  });
                }}
              />
            )}
          </div>
        </div>
      </Form>
    );
  },
);

StatCardEditor.displayName = "StatCardEditor";

export default StatCardEditor;
