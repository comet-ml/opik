import React, {
  useMemo,
  forwardRef,
  useImperativeHandle,
  useEffect,
  useCallback,
} from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import get from "lodash/get";
import { Filter, ListChecks } from "lucide-react";

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
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

import ExperimentWidgetDataSection from "@/components/shared/Dashboard/widgets/shared/ExperimentWidgetDataSection/ExperimentWidgetDataSection";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import WidgetOverrideDefaultsSection from "@/components/shared/Dashboard/widgets/shared/WidgetOverrideDefaultsSection/WidgetOverrideDefaultsSection";
import WidgetRankingSettingsSection from "@/components/shared/Dashboard/widgets/ExperimentLeaderboardWidget/WidgetRankingSettingsSection";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";

import { cn } from "@/lib/utils";
import { Filters } from "@/types/filters";

import {
  DashboardWidget,
  EXPERIMENT_DATA_SOURCE,
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
import {
  parseMetadataKeys,
  formatConfigColumnName,
  PREDEFINED_COLUMNS,
} from "./helpers";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useAppStore from "@/store/AppStore";
import { useExperimentsFeedbackScores } from "@/components/pages-shared/experiments/useExperimentsFeedbackScores";
import { COLUMN_METADATA_ID, COLUMN_TYPE } from "@/types/shared";

const DEFAULT_ROW_COUNT = 20;

const ExperimentLeaderboardWidgetEditor = forwardRef<WidgetEditorHandle>(
  (_, ref) => {
    const workspaceName = useAppStore((state) => state.activeWorkspaceName);
    const widgetData = useDashboardStore(
      (state) => state.previewWidget!,
    ) as DashboardWidget & ExperimentLeaderboardWidgetType;
    const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

    const { config } = widgetData;

    const dataSource =
      config.dataSource || EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

    const filters = useMemo(() => config.filters || [], [config.filters]);
    const experimentIds = useMemo(
      () => config.experimentIds || [],
      [config.experimentIds],
    );
    const selectedColumns = useMemo(
      () => config.selectedColumns || [],
      [config.selectedColumns],
    );
    const overrideDefaults = config.overrideDefaults || false;
    const enableRanking = config.enableRanking ?? true;
    const rankingMetric = config.rankingMetric;
    const columnsOrder = useMemo(
      () => config.columnsOrder || [],
      [config.columnsOrder],
    );
    const scoresColumnsOrder = useMemo(
      () => config.scoresColumnsOrder || [],
      [config.scoresColumnsOrder],
    );
    const metadataColumnsOrder = useMemo(
      () => config.metadataColumnsOrder || [],
      [config.metadataColumnsOrder],
    );
    const maxRows = config.maxRows || DEFAULT_ROW_COUNT;

    const form = useForm<ExperimentLeaderboardWidgetFormData>({
      resolver: zodResolver(ExperimentLeaderboardWidgetSchema),
      mode: "onTouched",
      defaultValues: {
        dataSource,
        filters,
        experimentIds,
        selectedColumns,
        overrideDefaults,
        enableRanking,
        rankingMetric,
        columnsOrder,
        scoresColumnsOrder,
        metadataColumnsOrder,
        maxRows,
      },
    });

    const currentFilters = form.watch("filters") || [];

    useEffect(() => {
      if (form.formState.errors.filters) {
        form.clearErrors("filters");
      }
    }, [currentFilters.length, form]);

    const { dynamicScoresColumns } = useExperimentsFeedbackScores({
      experimentIds:
        dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
          ? experimentIds
          : undefined,
      refetchInterval: 0,
    });

    const { data: experimentsData } = useExperimentsList(
      {
        workspaceName,
        experimentIds:
          dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
            ? experimentIds
            : undefined,
        filters:
          dataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP
            ? filters
            : undefined,
        page: 1,
        size: 100,
      },
      {
        enabled:
          overrideDefaults &&
          dataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP,
      },
    );

    const availableMetadataKeys = useMemo(() => {
      if (!experimentsData?.content) return [];
      return parseMetadataKeys(experimentsData.content);
    }, [experimentsData]);

    useImperativeHandle(ref, () => ({
      submit: async () => {
        return await form.trigger();
      },
      isValid: form.formState.isValid,
    }));

    const handleDataSourceChange = (value: string) => {
      const newDataSource = value as EXPERIMENT_DATA_SOURCE;
      form.setValue("dataSource", newDataSource);
      updatePreviewWidget({
        config: {
          ...config,
          dataSource: newDataSource,
        },
      });
    };

    const handleFiltersChange = (newFilters: Filters) => {
      updatePreviewWidget({
        config: {
          ...config,
          filters: newFilters,
        },
      });
    };

    const handleExperimentIdsChange = (newExperimentIds: string[]) => {
      form.setValue("experimentIds", newExperimentIds);
      updatePreviewWidget({
        config: {
          ...config,
          experimentIds: newExperimentIds,
        },
      });
    };

    const handleSelectedColumnsChange = (newSelectedColumns: string[]) => {
      form.setValue("selectedColumns", newSelectedColumns);
      updatePreviewWidget({
        config: {
          ...config,
          selectedColumns: newSelectedColumns,
        },
      });
    };

    const handleEnableRankingChange = (checked: boolean) => {
      form.setValue("enableRanking", checked);
      updatePreviewWidget({
        config: {
          ...config,
          enableRanking: checked,
        },
      });
    };

    const handleRankingMetricChange = (value: string) => {
      form.setValue("rankingMetric", value);
      updatePreviewWidget({
        config: {
          ...config,
          rankingMetric: value,
        },
      });
    };

    const handleColumnsOrderChange = (newOrder: string[]) => {
      form.setValue("columnsOrder", newOrder);
      updatePreviewWidget({
        config: {
          ...config,
          columnsOrder: newOrder,
        },
      });
    };

    const handleScoresColumnsOrderChange = useCallback(
      (newOrder: string[]) => {
        form.setValue("scoresColumnsOrder", newOrder);
        updatePreviewWidget({
          config: {
            ...config,
            scoresColumnsOrder: newOrder,
          },
        });
      },
      [form, config, updatePreviewWidget],
    );

    const handleMetadataColumnsOrderChange = useCallback(
      (newOrder: string[]) => {
        form.setValue("metadataColumnsOrder", newOrder);
        updatePreviewWidget({
          config: {
            ...config,
            metadataColumnsOrder: newOrder,
          },
        });
      },
      [form, config, updatePreviewWidget],
    );

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

    const dynamicMetadataColumns = useMemo(() => {
      return availableMetadataKeys.map((key) => ({
        id: `${COLUMN_METADATA_ID}.${key}`,
        label: formatConfigColumnName(key),
        columnType: COLUMN_TYPE.string,
      }));
    }, [availableMetadataKeys]);

    const columnSections = useMemo(
      () => [
        {
          title: "Configuration",
          columns: dynamicMetadataColumns,
          order: metadataColumnsOrder,
          onOrderChange: handleMetadataColumnsOrderChange,
        },
        {
          title: "Feedback scores",
          columns: dynamicScoresColumns,
          order: scoresColumnsOrder,
          onOrderChange: handleScoresColumnsOrderChange,
        },
      ],
      [
        dynamicMetadataColumns,
        dynamicScoresColumns,
        handleMetadataColumnsOrderChange,
        handleScoresColumnsOrderChange,
        metadataColumnsOrder,
        scoresColumnsOrder,
      ],
    );

    return (
      <Form {...form}>
        <WidgetEditorBaseLayout>
          <div className="space-y-4">
            <FormField
              control={form.control}
              name="selectedColumns"
              render={() => (
                <FormItem>
                  <FormLabel>Columns</FormLabel>
                  <FormControl>
                    <ColumnsButton
                      columns={PREDEFINED_COLUMNS}
                      selectedColumns={selectedColumns}
                      onSelectionChange={handleSelectedColumnsChange}
                      order={columnsOrder}
                      onOrderChange={handleColumnsOrderChange}
                      sections={columnSections}
                    />
                  </FormControl>
                  <Description>
                    Select and reorder columns to display
                  </Description>
                  <FormMessage />
                </FormItem>
              )}
            />

            <WidgetRankingSettingsSection
              control={form.control}
              enableRanking={enableRanking}
              dynamicScoresColumns={dynamicScoresColumns}
              onEnableRankingChange={handleEnableRankingChange}
              onRankingMetricChange={handleRankingMetricChange}
            />

            <WidgetOverrideDefaultsSection
              value={form.watch("overrideDefaults") || false}
              onChange={(value) => {
                form.setValue("overrideDefaults", value);
                updatePreviewWidget({
                  config: {
                    ...config,
                    overrideDefaults: value,
                  },
                });
              }}
              description="Turn this on to override the dashboard's default experiments for this widget."
            >
              <div className="space-y-4">
                <FormField
                  control={form.control}
                  name="dataSource"
                  render={({ field }) => (
                    <FormItem>
                      <FormLabel>Experiment override</FormLabel>
                      <FormControl>
                        <ToggleGroup
                          type="single"
                          variant="ghost"
                          value={
                            field.value ||
                            EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
                          }
                          onValueChange={(value) => {
                            if (value) {
                              field.onChange(value);
                              handleDataSourceChange(value);
                            }
                          }}
                          className="w-fit justify-start"
                        >
                          <ToggleGroupItem
                            value={EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS}
                            aria-label="Manual selection"
                            className="gap-1.5"
                          >
                            <ListChecks className="size-3.5" />
                            <span>Manual selection</span>
                          </ToggleGroupItem>
                          <ToggleGroupItem
                            value={EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP}
                            aria-label="Filter experiments"
                            className="gap-1.5"
                          >
                            <Filter className="size-3.5" />
                            <span>Filter experiments</span>
                          </ToggleGroupItem>
                        </ToggleGroup>
                      </FormControl>
                      <FormMessage />
                    </FormItem>
                  )}
                />

                {form.watch("dataSource") ===
                  EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP && (
                  <>
                    <ExperimentWidgetDataSection
                      control={form.control}
                      filtersFieldName="filters"
                      filters={filters}
                      onFiltersChange={handleFiltersChange}
                    />
                    <FormField
                      control={form.control}
                      name="maxRows"
                      render={({ field, formState }) => {
                        const validationErrors = get(formState.errors, [
                          "maxRows",
                        ]);
                        return (
                          <FormItem>
                            <FormLabel>Maximum rows</FormLabel>
                            <FormControl>
                              <Input
                                type="number"
                                min={1}
                                max={100}
                                value={field.value || DEFAULT_ROW_COUNT}
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
                              Number of experiments to display (1-100)
                            </Description>
                            <FormMessage />
                          </FormItem>
                        );
                      }}
                    />
                  </>
                )}

                {form.watch("dataSource") ===
                  EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS && (
                  <FormField
                    control={form.control}
                    name="experimentIds"
                    render={({ field, formState }) => {
                      const validationErrors = get(formState.errors, [
                        "experimentIds",
                      ]);
                      return (
                        <FormItem>
                          <FormLabel>Select experiments</FormLabel>
                          <FormControl>
                            <ExperimentsSelectBox
                              value={field.value || []}
                              onValueChange={(value) => {
                                field.onChange(value);
                                handleExperimentIdsChange(value);
                              }}
                              multiselect
                              showClearButton
                              className={cn("flex-1", {
                                "border-destructive": Boolean(
                                  validationErrors?.message,
                                ),
                              })}
                            />
                          </FormControl>
                          <Description>
                            Choose specific experiments to show in this widget.
                          </Description>
                          <FormMessage />
                        </FormItem>
                      );
                    }}
                  />
                )}
              </div>
            </WidgetOverrideDefaultsSection>
          </div>
        </WidgetEditorBaseLayout>
      </Form>
    );
  },
);

ExperimentLeaderboardWidgetEditor.displayName =
  "ExperimentLeaderboardWidgetEditor";

export default ExperimentLeaderboardWidgetEditor;
