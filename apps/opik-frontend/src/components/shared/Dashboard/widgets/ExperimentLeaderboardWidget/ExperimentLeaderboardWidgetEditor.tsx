import React, {
  useMemo,
  forwardRef,
  useImperativeHandle,
  useEffect,
  useState,
  useCallback,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import get from "lodash/get";

import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Description } from "@/components/ui/description";
import { Input } from "@/components/ui/input";
import { Checkbox } from "@/components/ui/checkbox";
import { Tag } from "@/components/ui/tag";
import { Button } from "@/components/ui/button";
import { X, Plus } from "lucide-react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import FiltersAccordionSection from "@/components/shared/FiltersAccordionSection/FiltersAccordionSection";
import DatasetSelectBox from "@/components/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import ExperimentsPathsAutocomplete from "@/components/pages-shared/experiments/ExperimentsPathsAutocomplete/ExperimentsPathsAutocomplete";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/components/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";

import { cn } from "@/lib/utils";
import { Filters } from "@/types/filters";
import { FilterOperator } from "@/types/filters";
import { OnChangeFn } from "@/types/shared";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";

import {
  DashboardWidget,
  ExperimentLeaderboardWidgetType,
  WidgetEditorHandle,
} from "@/types/dashboard";
import {
  useDashboardStore,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";
import {
  ExperimentLeaderboardWidgetSchema,
  ExperimentLeaderboardWidgetFormData,
} from "./schema";
import WidgetEditorBaseLayout from "@/components/shared/Dashboard/WidgetConfigDialog/WidgetEditorBaseLayout";

const SORT_ORDER_OPTIONS = [
  { value: "desc", label: "Descending (highest first)" },
  { value: "asc", label: "Ascending (lowest first)" },
];

const STANDARD_COLUMNS = [
  { id: "name", label: "Experiment name", category: "standard", alwaysVisible: true },
  { id: "dataset", label: "Dataset", category: "standard", alwaysVisible: false },
  { id: "duration", label: "Duration", category: "efficiency", alwaysVisible: false },
  { id: "cost", label: "Cost", category: "efficiency", alwaysVisible: false },
  { id: "trace_count", label: "Trace count", category: "efficiency", alwaysVisible: false },
  { id: "created_at", label: "Created at", category: "standard", alwaysVisible: false },
];

const EXPERIMENT_DATA_COLUMNS: ColumnData<{ id: string; dataset_id?: string }>[] = [
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

const ExperimentLeaderboardWidgetEditor = forwardRef<WidgetEditorHandle>(
  (_, ref) => {
    const widgetData = useDashboardStore(
      (state) => state.previewWidget!,
    ) as DashboardWidget & ExperimentLeaderboardWidgetType;
    const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

    const { config } = widgetData;

    const filters = useMemo(() => config.filters || [], [config.filters]);

    const selectedMetrics = useMemo(
      () => config.selectedMetrics || [],
      [config.selectedMetrics],
    );

    const primaryMetric = config.primaryMetric || "accuracy";
    const sortOrder = config.sortOrder || "desc";
    const showRank = config.showRank !== false;
    const maxRows = config.maxRows || 20;
    const displayColumns = useMemo(
      () => config.displayColumns || ["name", "dataset", "duration", "cost", "trace_count"],
      [config.displayColumns],
    );
    const metadataColumns = useMemo(
      () => config.metadataColumns || [],
      [config.metadataColumns],
    );
    const columnsOrder = useMemo(
      () => config.columnsOrder || [],
      [config.columnsOrder],
    );

    const form = useForm<ExperimentLeaderboardWidgetFormData>({
      resolver: zodResolver(ExperimentLeaderboardWidgetSchema),
      mode: "onTouched",
      defaultValues: {
        filters,
        selectedMetrics,
        primaryMetric,
        sortOrder,
        showRank,
        maxRows,
        displayColumns,
        metadataColumns,
        columnsOrder,
      },
    });

    const currentFilters = form.watch("filters") || [];
    const [metadataInput, setMetadataInput] = useState("");

    // Get available metrics from experiments
    const { data: experimentsScoresData } = useExperimentsFeedbackScoresNames(
      {}, // Get all available metrics
      {
        enabled: true,
      },
    );

    // Get all available metrics
    const availableMetrics = useMemo(() => {
      return (experimentsScoresData?.scores || []).map((score) => score.name);
    }, [experimentsScoresData]);

    // Format metric name for display
    const formatMetricName = useCallback((metricName: string): string => {
      const names: Record<string, string> = {
        duration: "Duration",
        cost: "Cost",
        trace_count: "Traces",
        hallucination_rate: "Hallucination",
        mean_score: "Mean Score",
      };
      if (names[metricName]) return names[metricName];
      return metricName
        .split("_")
        .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
        .join(" ");
    }, []);

    // Create unified list of all available columns
    const allAvailableColumns = useMemo(() => {
      const columns: Array<{
        id: string;
        label: string;
        category: "standard" | "quality" | "efficiency" | "metadata";
        type: "column" | "metric" | "metadata";
        alwaysVisible?: boolean;
      }> = [];

      // Standard columns
      STANDARD_COLUMNS.forEach((col) => {
        columns.push({
          id: col.id,
          label: col.label,
          category: col.category as "standard" | "efficiency",
          type: "column",
          alwaysVisible: col.alwaysVisible,
        });
      });

      // Metric columns (quality metrics)
      const qualityMetrics = ["accuracy", "hallucination_rate", "relevance", "helpfulness", "mean_score"];
      availableMetrics.forEach((metricName: string) => {
        const category = qualityMetrics.includes(metricName) ? "quality" : "efficiency";
        columns.push({
          id: `metric_${metricName}`,
          label: formatMetricName(metricName),
          category,
          type: "metric",
        });
      });

      // Add system metrics if not already in availableMetrics
      if (!availableMetrics.includes("duration")) {
        columns.push({
          id: "duration",
          label: "Duration",
          category: "efficiency",
          type: "metric",
        });
      }
      if (!availableMetrics.includes("cost")) {
        columns.push({
          id: "cost",
          label: "Cost",
          category: "efficiency",
          type: "metric",
        });
      }
      if (!availableMetrics.includes("trace_count")) {
        columns.push({
          id: "trace_count",
          label: "Trace count",
          category: "efficiency",
          type: "metric",
        });
      }

      // Metadata columns (user-defined)
      metadataColumns.forEach((metadataKey) => {
        columns.push({
          id: `metadata_${metadataKey}`,
          label: metadataKey
            .split("_")
            .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
            .join(" "),
          category: "metadata",
          type: "metadata",
        });
      });

      return columns;
    }, [availableMetrics, formatMetricName, metadataColumns]);

    useEffect(() => {
      if (form.formState.errors.filters) {
        form.clearErrors("filters");
      }
    }, [currentFilters.length, form]);

    useImperativeHandle(ref, () => ({
      submit: async () => {
        return await form.trigger();
      },
      isValid: form.formState.isValid,
    }));

    const handleFiltersChange: OnChangeFn<Filters> = (updaterOrValue) => {
      const newFilters = typeof updaterOrValue === "function" 
        ? updaterOrValue(filters) 
        : updaterOrValue;
      form.setValue("filters", newFilters);
      updatePreviewWidget({
        config: {
          ...config,
          filters: newFilters,
        },
      });
    };

    const handleSelectedMetricsChange = (newMetrics: string[]) => {
      form.setValue("selectedMetrics", newMetrics);
      updatePreviewWidget({
        config: {
          ...config,
          selectedMetrics: newMetrics,
        },
      });
    };

    const handlePrimaryMetricChange = (newMetric: string) => {
      form.setValue("primaryMetric", newMetric);
      updatePreviewWidget({
        config: {
          ...config,
          primaryMetric: newMetric,
        },
      });
    };

    const handleSortOrderChange = (newSortOrder: string) => {
      form.setValue("sortOrder", newSortOrder as "asc" | "desc");
      updatePreviewWidget({
        config: {
          ...config,
          sortOrder: newSortOrder as "asc" | "desc",
        },
      });
    };

    const handleShowRankChange = (checked: boolean) => {
      form.setValue("showRank", checked);
      updatePreviewWidget({
        config: {
          ...config,
          showRank: checked,
        },
      });
    };

    const handleMaxRowsChange = (value: string) => {
      const numValue = parseInt(value, 10);
      if (!isNaN(numValue) && numValue >= 1 && numValue <= 100) {
        form.setValue("maxRows", numValue);
        updatePreviewWidget({
          config: {
            ...config,
            maxRows: numValue,
          },
        });
      }
    };

    const handleDisplayColumnsChange = (newColumns: string[]) => {
      form.setValue("displayColumns", newColumns);
      updatePreviewWidget({
        config: {
          ...config,
          displayColumns: newColumns,
        },
      });
    };

    const handleMetadataColumnsChange = (newMetadataColumns: string[]) => {
      form.setValue("metadataColumns", newMetadataColumns);
      updatePreviewWidget({
        config: {
          ...config,
          metadataColumns: newMetadataColumns,
        },
      });
    };

    const handleAddMetadataColumn = () => {
      const trimmedInput = metadataInput.trim();
      if (trimmedInput && !metadataColumns.includes(trimmedInput)) {
        const newMetadataColumns = [...metadataColumns, trimmedInput];
        handleMetadataColumnsChange(newMetadataColumns);
        setMetadataInput("");
      }
    };

    const handleRemoveMetadataColumn = (columnToRemove: string) => {
      const newMetadataColumns = metadataColumns.filter(
        (col) => col !== columnToRemove,
      );
      handleMetadataColumnsChange(newMetadataColumns);
    };

    const handleMetadataInputKeyDown = (
      e: React.KeyboardEvent<HTMLInputElement>,
    ) => {
      if (e.key === "Enter") {
        e.preventDefault();
        handleAddMetadataColumn();
      }
    };

    // Get available metrics for primary metric selection
    // In prototype, we use a fixed list
    const availablePrimaryMetrics = [
      { value: "accuracy", label: "Accuracy" },
      { value: "hallucination_rate", label: "Hallucination Rate" },
      { value: "relevance", label: "Relevance" },
      { value: "helpfulness", label: "Helpfulness" },
      { value: "mean_score", label: "Mean Score" },
      { value: "duration", label: "Duration" },
      { value: "cost", label: "Cost" },
      { value: "trace_count", label: "Trace Count" },
    ];

    return (
      <Form {...form}>
        <WidgetEditorBaseLayout>
          <div className="space-y-6">
            {/* Data Source Section */}
            <div className="space-y-4">
              <div>
                <h3 className="comet-title-xs mb-1">Data source</h3>
                <p className="comet-body-s text-light-slate">
                  Filter which experiments appear in the leaderboard
                </p>
              </div>
              <FiltersAccordionSection
                columns={EXPERIMENT_DATA_COLUMNS as ColumnData<unknown>[]}
                config={{
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
                }}
                filters={filters}
                onChange={handleFiltersChange}
                label="Filters"
              />
            </div>

            {/* Ranking Section */}
            <div className="space-y-4">
              <div>
                <h3 className="comet-title-xs mb-1">Ranking</h3>
                <p className="comet-body-s text-light-slate">
                  Choose which metric to use for ranking experiments
                </p>
              </div>

              <FormField
                control={form.control}
                name="primaryMetric"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "primaryMetric",
                  ]);
                  return (
                    <FormItem>
                      <FormLabel>Primary ranking metric</FormLabel>
                      <FormControl>
                        <SelectBox
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          value={field.value || "accuracy"}
                          onChange={(value) => {
                            field.onChange(value);
                            handlePrimaryMetricChange(value);
                          }}
                          options={availablePrimaryMetrics}
                          placeholder="Select ranking metric"
                        />
                      </FormControl>
                      <Description>
                        Experiments will be ranked by this metric by default
                      </Description>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
            </div>

            {/* Columns Section - Unified Selector */}
            <div className="space-y-4">
              <div>
                <h3 className="comet-title-xs mb-1">Columns</h3>
                <p className="comet-body-s text-light-slate">
                  Select which columns to display in the leaderboard
                </p>
              </div>

              <FormField
                control={form.control}
                name="displayColumns"
                render={({ field: displayColumnsField }) => (
                  <FormField
                    control={form.control}
                    name="selectedMetrics"
                    render={({ field: metricsField }) => {
                      // Helper to check if a column is selected
                      const isColumnSelected = (column: typeof allAvailableColumns[0]): boolean => {
                        if (column.type === "column") {
                          return displayColumns.includes(column.id) || column.alwaysVisible || false;
                        }
                        if (column.type === "metric") {
                          const metricName = column.id.replace("metric_", "");
                          // If selectedMetrics is empty, all metrics are shown
                          if (selectedMetrics.length === 0) return true;
                          return selectedMetrics.includes(metricName);
                        }
                        if (column.type === "metadata") {
                          const metadataKey = column.id.replace("metadata_", "");
                          return metadataColumns.includes(metadataKey);
                        }
                        return false;
                      };

                      // Helper to toggle column selection
                      const handleColumnToggle = (
                        column: typeof allAvailableColumns[0],
                        checked: boolean,
                      ) => {
                        if (column.alwaysVisible) return; // Can't toggle always-visible columns

                        if (column.type === "column") {
                          const currentColumns = displayColumns || [];
                          const newColumns = checked
                            ? [...currentColumns, column.id]
                            : currentColumns.filter((id) => id !== column.id);
                          displayColumnsField.onChange(newColumns);
                          handleDisplayColumnsChange(newColumns);
                        } else if (column.type === "metric") {
                          const metricName = column.id.replace("metric_", "");
                          const currentMetrics = selectedMetrics || [];
                          let newMetrics: string[];
                          
                          if (checked) {
                            // Add metric
                            newMetrics = [...currentMetrics, metricName];
                          } else {
                            // Remove metric - if this would empty the array, we need special handling
                            newMetrics = currentMetrics.filter((m) => m !== metricName);
                            // If we're removing the last metric, we should keep at least one
                            // Actually, empty array means "show all", so this is fine
                          }
                          metricsField.onChange(newMetrics);
                          handleSelectedMetricsChange(newMetrics);
                        }
                        // Metadata columns are handled separately via the add/remove buttons
                      };

                      // Group columns by category
                      const groupedColumns = useMemo(() => {
                        const groups: Record<string, typeof allAvailableColumns> = {
                          standard: [],
                          quality: [],
                          efficiency: [],
                          metadata: [],
                        };
                        allAvailableColumns.forEach((col) => {
                          groups[col.category].push(col);
                        });
                        return groups;
                      }, [allAvailableColumns]);

                      // Check if all columns in a category are selected
                      const areAllInCategorySelected = (category: string): boolean => {
                        const cols = groupedColumns[category] || [];
                        if (cols.length === 0) return false;
                        return cols.every((col) => isColumnSelected(col) || col.alwaysVisible);
                      };

                      // Toggle all columns in a category
                      const handleCategoryToggle = (category: string, checked: boolean) => {
                        const cols = groupedColumns[category] || [];
                        cols.forEach((col) => {
                          if (!col.alwaysVisible) {
                            handleColumnToggle(col, checked);
                          }
                        });
                      };

                      return (
                        <FormItem>
                          <FormLabel>Select columns</FormLabel>
                          <FormControl>
                            <div className="space-y-4 rounded-md border p-4">
                              {/* Standard Columns */}
                              {groupedColumns.standard.length > 0 && (
                                <div className="space-y-2">
                                  <div className="flex items-center justify-between">
                                    <h4 className="text-sm font-medium">Standard Columns</h4>
                                    <button
                                      type="button"
                                      onClick={() =>
                                        handleCategoryToggle(
                                          "standard",
                                          !areAllInCategorySelected("standard"),
                                        )
                                      }
                                      className="text-xs text-primary hover:underline"
                                    >
                                      {areAllInCategorySelected("standard")
                                        ? "Deselect all"
                                        : "Select all"}
                                    </button>
                                  </div>
                                  <div className="space-y-2 pl-2">
                                    {groupedColumns.standard.map((column) => {
                                      const isChecked = isColumnSelected(column);
                                      const isDisabled = column.alwaysVisible;
                                      return (
                                        <div
                                          key={column.id}
                                          className="flex items-center space-x-2"
                                        >
                                          <Checkbox
                                            id={`column-${column.id}`}
                                            checked={isChecked || isDisabled}
                                            disabled={isDisabled}
                                            onCheckedChange={(checked) =>
                                              handleColumnToggle(column, checked as boolean)
                                            }
                                          />
                                          <label
                                            htmlFor={`column-${column.id}`}
                                            className={cn(
                                              "text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70",
                                              isDisabled && "text-muted-foreground",
                                            )}
                                          >
                                            {column.label}
                                            {isDisabled && " (always visible)"}
                                          </label>
                                        </div>
                                      );
                                    })}
                                  </div>
                                </div>
                              )}

                              {/* Quality Metrics */}
                              {groupedColumns.quality.length > 0 && (
                                <div className="space-y-2 border-t pt-4">
                                  <div className="flex items-center justify-between">
                                    <h4 className="text-sm font-medium">Quality Metrics</h4>
                                    <button
                                      type="button"
                                      onClick={() =>
                                        handleCategoryToggle(
                                          "quality",
                                          !areAllInCategorySelected("quality"),
                                        )
                                      }
                                      className="text-xs text-primary hover:underline"
                                    >
                                      {areAllInCategorySelected("quality")
                                        ? "Deselect all"
                                        : "Select all"}
                                    </button>
                                  </div>
                                  <div className="space-y-2 pl-2">
                                    {groupedColumns.quality.map((column) => {
                                      const isChecked = isColumnSelected(column);
                                      return (
                                        <div
                                          key={column.id}
                                          className="flex items-center space-x-2"
                                        >
                                          <Checkbox
                                            id={`column-${column.id}`}
                                            checked={isChecked}
                                            onCheckedChange={(checked) =>
                                              handleColumnToggle(column, checked as boolean)
                                            }
                                          />
                                          <label
                                            htmlFor={`column-${column.id}`}
                                            className="text-sm font-medium leading-none"
                                          >
                                            {column.label}
                                          </label>
                                        </div>
                                      );
                                    })}
                                  </div>
                                </div>
                              )}

                              {/* Efficiency Metrics */}
                              {groupedColumns.efficiency.length > 0 && (
                                <div className="space-y-2 border-t pt-4">
                                  <div className="flex items-center justify-between">
                                    <h4 className="text-sm font-medium">Efficiency Metrics</h4>
                                    <button
                                      type="button"
                                      onClick={() =>
                                        handleCategoryToggle(
                                          "efficiency",
                                          !areAllInCategorySelected("efficiency"),
                                        )
                                      }
                                      className="text-xs text-primary hover:underline"
                                    >
                                      {areAllInCategorySelected("efficiency")
                                        ? "Deselect all"
                                        : "Select all"}
                                    </button>
                                  </div>
                                  <div className="space-y-2 pl-2">
                                    {groupedColumns.efficiency.map((column) => {
                                      const isChecked = isColumnSelected(column);
                                      return (
                                        <div
                                          key={column.id}
                                          className="flex items-center space-x-2"
                                        >
                                          <Checkbox
                                            id={`column-${column.id}`}
                                            checked={isChecked}
                                            onCheckedChange={(checked) =>
                                              handleColumnToggle(column, checked as boolean)
                                            }
                                          />
                                          <label
                                            htmlFor={`column-${column.id}`}
                                            className="text-sm font-medium leading-none"
                                          >
                                            {column.label}
                                          </label>
                                        </div>
                                      );
                                    })}
                                  </div>
                                </div>
                              )}

                              {/* Metadata Columns */}
                              <div className="space-y-2 border-t pt-4">
                                <div className="flex items-center justify-between">
                                  <h4 className="text-sm font-medium">Configuration Columns</h4>
                                </div>
                                <div className="space-y-2 pl-2">
                                  <div className="flex gap-2">
                                    <Input
                                      placeholder="e.g., provider, model_name"
                                      value={metadataInput}
                                      onChange={(e) => setMetadataInput(e.target.value)}
                                      onKeyDown={handleMetadataInputKeyDown}
                                      className="flex-1"
                                    />
                                    <Button
                                      type="button"
                                      variant="outline"
                                      size="icon"
                                      onClick={handleAddMetadataColumn}
                                      disabled={!metadataInput.trim()}
                                    >
                                      <Plus className="size-4" />
                                    </Button>
                                  </div>
                                  {metadataColumns.length > 0 && (
                                    <div className="space-y-2">
                                      {metadataColumns.map((metadataKey) => {
                                        const column = allAvailableColumns.find(
                                          (c) => c.id === `metadata_${metadataKey}`,
                                        );
                                        if (!column) return null;
                                        const isChecked = isColumnSelected(column);
                                        return (
                                          <div
                                            key={metadataKey}
                                            className="flex items-center space-x-2"
                                          >
                                            <Checkbox
                                              id={`column-metadata-${metadataKey}`}
                                              checked={isChecked}
                                              onCheckedChange={(checked) => {
                                                if (!checked) {
                                                  handleRemoveMetadataColumn(metadataKey);
                                                }
                                              }}
                                            />
                                            <label
                                              htmlFor={`column-metadata-${metadataKey}`}
                                              className="text-sm font-medium leading-none"
                                            >
                                              {column.label}
                                            </label>
                                            <button
                                              type="button"
                                              onClick={() =>
                                                handleRemoveMetadataColumn(metadataKey)
                                              }
                                              className="ml-auto rounded-sm p-1 hover:bg-muted"
                                            >
                                              <X className="size-3" />
                                            </button>
                                          </div>
                                        );
                                      })}
                                    </div>
                                  )}
                                </div>
                              </div>
                            </div>
                          </FormControl>
                          <Description>
                            Select which columns to display. By default, all available metrics will be shown.
                          </Description>
                          <FormMessage />
                        </FormItem>
                      );
                    }}
                  />
                )}
              />
            </div>

            {/* Display Options Section */}
            <div className="space-y-4">
              <div>
                <h3 className="comet-title-xs mb-1">Display options</h3>
                <p className="comet-body-s text-light-slate">
                  Customize how the leaderboard is displayed
                </p>
              </div>

              <FormField
                control={form.control}
                name="sortOrder"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["sortOrder"]);
                  return (
                    <FormItem>
                      <FormLabel>Sort order</FormLabel>
                      <FormControl>
                        <SelectBox
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          value={field.value || "desc"}
                          onChange={(value) => {
                            field.onChange(value);
                            handleSortOrderChange(value);
                          }}
                          options={SORT_ORDER_OPTIONS}
                          placeholder="Select sort order"
                        />
                      </FormControl>
                      <Description>
                        Default sort direction for the primary metric
                      </Description>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />

              <FormField
                control={form.control}
                name="showRank"
                render={({ field }) => (
                  <FormItem className="flex flex-row items-start space-x-3 space-y-0">
                    <FormControl>
                      <Checkbox
                        checked={field.value}
                        onCheckedChange={(checked) => {
                          field.onChange(checked);
                          handleShowRankChange(checked as boolean);
                        }}
                      />
                    </FormControl>
                    <div className="space-y-1 leading-none">
                      <FormLabel>Show rank column</FormLabel>
                      <Description>
                        Display rank numbers (1, 2, 3...) in the first column
                      </Description>
                    </div>
                  </FormItem>
                )}
              />

              <FormField
                control={form.control}
                name="maxRows"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, ["maxRows"]);
                  return (
                    <FormItem>
                      <FormLabel>Max rows to display</FormLabel>
                      <FormControl>
                        <Input
                          type="number"
                          min={1}
                          max={100}
                          value={field.value}
                          onChange={(e) => {
                            field.onChange(e.target.value);
                            handleMaxRowsChange(e.target.value);
                          }}
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                        />
                      </FormControl>
                      <Description>
                        Limit the number of experiments shown (1-100)
                      </Description>
                      <FormMessage />
                    </FormItem>
                  );
                }}
              />
            </div>
          </div>
        </WidgetEditorBaseLayout>
      </Form>
    );
  },
);

ExperimentLeaderboardWidgetEditor.displayName =
  "ExperimentLeaderboardWidgetEditor";

export default ExperimentLeaderboardWidgetEditor;

