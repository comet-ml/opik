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
import isEmpty from "lodash/isEmpty";
import isNumber from "lodash/isNumber";
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
import { Label } from "@/components/ui/label";
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
  selectConfig,
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
  DEFAULT_MAX_ROWS,
  MIN_MAX_ROWS,
  MAX_MAX_ROWS,
  getExperimentListParams,
} from "./helpers";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useAppStore from "@/store/AppStore";
import { useExperimentsFeedbackScores } from "@/components/pages-shared/experiments/useExperimentsFeedbackScores";
import { COLUMN_METADATA_ID, COLUMN_TYPE } from "@/types/shared";

const ExperimentLeaderboardWidgetEditor = forwardRef<WidgetEditorHandle>(
  (_, ref) => {
    const workspaceName = useAppStore((state) => state.activeWorkspaceName);
    const widgetData = useDashboardStore(
      (state) => state.previewWidget!,
    ) as DashboardWidget & ExperimentLeaderboardWidgetType;
    const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);
    const globalConfig = useDashboardStore(selectConfig);

    const { config } = widgetData;

    const dataSource =
      config.dataSource || EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

    const filters = config.filters || [];
    const overrideDefaults = config.overrideDefaults || false;
    const selectedColumns = config.selectedColumns || [];

    const experimentIds = useMemo(() => {
      if (overrideDefaults) {
        return config.experimentIds || [];
      }
      return globalConfig?.experimentIds || [];
    }, [globalConfig?.experimentIds, config.experimentIds, overrideDefaults]);

    const enableRanking = config.enableRanking ?? true;
    const rankingMetric = config.rankingMetric;
    const rankingDirection = config.rankingDirection ?? true;
    const columnsOrder = config.columnsOrder || [];

    const scoresColumnsOrder = useMemo(
      () => config.scoresColumnsOrder || [],
      [config.scoresColumnsOrder],
    );

    const metadataColumnsOrder = useMemo(
      () => config.metadataColumnsOrder || [],
      [config.metadataColumnsOrder],
    );

    const maxRows = config.maxRows || DEFAULT_MAX_ROWS;

    const form = useForm<ExperimentLeaderboardWidgetFormData>({
      resolver: zodResolver(ExperimentLeaderboardWidgetSchema),
      mode: "onTouched",
      defaultValues: {
        dataSource,
        filters,
        experimentIds: config.experimentIds || [],
        selectedColumns,
        overrideDefaults,
        enableRanking,
        rankingMetric,
        rankingDirection,
        columnsOrder,
        scoresColumnsOrder,
        metadataColumnsOrder,
        maxRows: String(maxRows),
      },
    });

    const currentFilters = form.watch("filters") || [];
    const watchedDataSource = form.watch("dataSource");

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

    const experimentListParams = getExperimentListParams({
      dataSource,
      experimentIds,
      filters,
    });

    const { data: experimentsData } = useExperimentsList(
      {
        workspaceName,
        experimentIds: experimentListParams.experimentIds,
        filters: experimentListParams.filters,
        page: 1,
        size: 100,
      },
      {
        enabled: experimentListParams.isEnabled,
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
      if (!checked) {
        form.clearErrors("rankingMetric");
      }
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

    const handleRankingHigherIsBetterChange = (value: boolean) => {
      form.setValue("rankingDirection", value);
      updatePreviewWidget({
        config: {
          ...config,
          rankingDirection: value,
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
      [config, form, updatePreviewWidget],
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
      [config, form, updatePreviewWidget],
    );

    const handleMaxRowsChange = (value: string) => {
      const numValue = isEmpty(value) ? undefined : parseInt(value, 10);
      updatePreviewWidget({
        config: {
          ...config,
          maxRows: isNumber(numValue) ? numValue : undefined,
        },
      });
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
          <div className="space-y-3">
            <div className="flex items-start justify-between">
              <div className="flex-1 pr-4">
                <div className="flex flex-col gap-0.5 px-0.5">
                  <Label className="comet-body-s-accented">Columns</Label>
                  <Description>
                    Select and reorder columns to display.
                  </Description>
                </div>
              </div>
              <ColumnsButton
                columns={PREDEFINED_COLUMNS}
                selectedColumns={selectedColumns}
                onSelectionChange={handleSelectedColumnsChange}
                order={columnsOrder}
                onOrderChange={handleColumnsOrderChange}
                sections={columnSections}
              />
            </div>

            <WidgetRankingSettingsSection
              control={form.control}
              dynamicScoresColumns={dynamicScoresColumns}
              onEnableRankingChange={handleEnableRankingChange}
              onRankingMetricChange={handleRankingMetricChange}
              onRankingHigherIsBetterChange={handleRankingHigherIsBetterChange}
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

                {watchedDataSource ===
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
                                min={MIN_MAX_ROWS}
                                max={MAX_MAX_ROWS}
                                value={field.value ?? ""}
                                onChange={(e) => {
                                  const value = e.target.value;
                                  field.onChange(value);
                                  handleMaxRowsChange(value);
                                }}
                                className={cn({
                                  "border-destructive": Boolean(
                                    validationErrors?.message,
                                  ),
                                })}
                              />
                            </FormControl>
                            <Description>
                              Number of experiments to display ({MIN_MAX_ROWS}-
                              {MAX_MAX_ROWS})
                            </Description>
                            <FormMessage />
                          </FormItem>
                        );
                      }}
                    />
                  </>
                )}

                {watchedDataSource ===
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
