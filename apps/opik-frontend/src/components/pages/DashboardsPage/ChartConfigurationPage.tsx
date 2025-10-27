import React, { useState, useCallback, useEffect, useMemo } from "react";
import { useNavigate, useParams } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Card, CardContent } from "@/components/ui/card";
import {
  ChartType,
  DashboardChart,
  DataSeries,
  GroupByConfig,
  GroupByType,
} from "@/types/dashboards";
import useChartCreateMutation from "@/api/dashboards/useChartCreateMutation";
import useChartUpdateMutation from "@/api/dashboards/useChartUpdateMutation";
import useChartById from "@/api/dashboards/useChartById";
import useChartPreviewDataQuery from "@/api/dashboards/useChartPreviewDataQuery";
import useProjectsList from "@/api/projects/useProjectsList";
import useAppStore from "@/store/AppStore";
import Loader from "@/components/shared/Loader/Loader";
import ChartPreview from "./ChartPreview";
import { Plus, Trash2, ArrowLeft, LineChart as LineChartIcon, BarChart3 } from "lucide-react";
import { Separator } from "@/components/ui/separator";

const METRIC_OPTIONS = [
  // Existing metrics
  { value: "TRACE_COUNT", label: "Trace Count" },
  { value: "FEEDBACK_SCORES", label: "Feedback Scores" },
  { value: "TOKEN_USAGE", label: "Token Usage" },
  { value: "COST", label: "Cost" },
  { value: "DURATION", label: "Duration" },
  { value: "GUARDRAILS_FAILED_COUNT", label: "Failed Guardrails" },
  { value: "THREAD_COUNT", label: "Thread Count" },
  { value: "THREAD_DURATION", label: "Thread Duration" },
  { value: "THREAD_FEEDBACK_SCORES", label: "Thread Feedback Scores" },
  
  // Easy additions
  { value: "ERROR_COUNT", label: "Error Count" },
  { value: "SPAN_COUNT", label: "Span Count" },
  { value: "LLM_SPAN_COUNT", label: "LLM Span Count" },
  
  // Medium additions - token metrics
  { value: "COMPLETION_TOKENS", label: "Completion Tokens" },
  { value: "PROMPT_TOKENS", label: "Prompt Tokens" },
  { value: "TOTAL_TOKENS", label: "Total Tokens" },
  
  // Medium additions - count metrics
  { value: "INPUT_COUNT", label: "Input Count" },
  { value: "OUTPUT_COUNT", label: "Output Count" },
  { value: "METADATA_COUNT", label: "Metadata Count" },
  { value: "TAGS_AVERAGE", label: "Tags Average" },
  
  // Medium additions - calculated metrics
  { value: "TRACE_WITH_ERRORS_PERCENT", label: "Trace Error Rate (%)" },
  { value: "GUARDRAILS_PASS_RATE", label: "Guardrails Pass Rate (%)" },
  { value: "AVG_COST_PER_TRACE", label: "Avg Cost Per Trace" },
  
  // Medium additions - span duration
  { value: "SPAN_DURATION", label: "Span Duration" },
];

const ChartConfigurationPage: React.FC = () => {
  const navigate = useNavigate();
  const { dashboardId, chartId, projectId } = useParams({ strict: false }) as {
    dashboardId: string;
    chartId?: string;
    projectId: string;
  };
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  
  const isEditMode = Boolean(chartId);

  // State
  const [chartName, setChartName] = useState("");
  const [chartDescription, setChartDescription] = useState("");
  const [chartType, setChartType] = useState<ChartType>("line");
  const [dataSeries, setDataSeries] = useState<DataSeries[]>([]);
  const [groupBy, setGroupBy] = useState<GroupByConfig | undefined>(undefined);
  const [enableGrouping, setEnableGrouping] = useState(false);

  // Queries
  const { data: existingChart, isPending: isLoadingChart } = useChartById(
    { dashboardId: dashboardId || "", chartId: chartId || "" },
    { enabled: isEditMode && Boolean(chartId) }
  );

  const { data: projectsData } = useProjectsList({ workspaceName, page: 1, size: 100 });
  const projects = projectsData?.content || [];

  const createChartMutation = useChartCreateMutation();
  const updateChartMutation = useChartUpdateMutation();

  // Load existing chart data
  useEffect(() => {
    if (existingChart) {
      setChartName(existingChart.name);
      setChartDescription(existingChart.description || "");
      setChartType(existingChart.chart_type);
      setDataSeries(existingChart.data_series || []);
      setGroupBy(existingChart.group_by);
      setEnableGrouping(Boolean(existingChart.group_by));
    }
  }, [existingChart]);

  // Add new data series
  const handleAddSeries = useCallback(() => {
    const newSeries: DataSeries = {
      project_id: projects[0]?.id || "",
      metric_type: "TRACE_COUNT",
      name: `Series ${dataSeries.length + 1}`,
      filters: [],
      color: `#${Math.floor(Math.random() * 16777215).toString(16)}`,
      order: dataSeries.length,
    };
    setDataSeries([...dataSeries, newSeries]);
  }, [dataSeries, projects]);

  // Remove data series
  const handleRemoveSeries = useCallback(
    (index: number) => {
      setDataSeries(dataSeries.filter((_, i) => i !== index));
    },
    [dataSeries]
  );

  // Update data series
  const handleUpdateSeries = useCallback(
    (index: number, updates: Partial<DataSeries>) => {
      const newSeries = [...dataSeries];
      newSeries[index] = { ...newSeries[index], ...updates };
      setDataSeries(newSeries);
    },
    [dataSeries]
  );

  // Save chart
  const handleSave = useCallback(async () => {
    const chartData: Partial<DashboardChart> = {
      name: chartName,
      description: chartDescription || undefined,
      chart_type: chartType,
      data_series: dataSeries,
      group_by: enableGrouping ? groupBy : undefined,
    };

    if (isEditMode && chartId) {
      await updateChartMutation.mutateAsync({
        dashboardId: dashboardId || "",
        chartId,
        chart: chartData,
      });
    } else {
      await createChartMutation.mutateAsync({
        dashboardId: dashboardId || "",
        chart: chartData,
      });
    }

    navigate({
      to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId",
      params: { workspaceName, projectId, dashboardId: dashboardId || "" },
    });
  }, [
    chartName,
    chartDescription,
    chartType,
    dataSeries,
    enableGrouping,
    groupBy,
    isEditMode,
    chartId,
    dashboardId,
    createChartMutation,
    updateChartMutation,
    navigate,
    workspaceName,
  ]);

  // Preview chart data
  const previewRequest = useMemo(
    () => ({
      interval: "DAILY" as const,
      interval_start: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString(),
      interval_end: new Date().toISOString(),
    }),
    []
  );

  // Build chart configuration for preview
  const previewChart = useMemo(
    () => ({
      name: chartName || "Preview Chart",
      chart_type: chartType,
      data_series: dataSeries,
      group_by: enableGrouping && groupBy ? groupBy : undefined,
    }),
    [chartName, chartType, dataSeries, enableGrouping, groupBy]
  );

  const {
    data: previewData,
    isPending: isLoadingPreview,
    isError: previewError,
  } = useChartPreviewDataQuery(
    {
      dashboardId: dashboardId || "",
      chart: previewChart,
      request: previewRequest,
      workspaceName,
    },
    { 
      enabled: dataSeries.length > 0 && Boolean(dashboardId),
      refetchOnMount: false,
      refetchOnWindowFocus: false,
    }
  );

  if (isEditMode && isLoadingChart) {
    return (
      <div className="flex h-screen items-center justify-center">
        <Loader />
      </div>
    );
  }

  return (
    <div className="flex h-screen flex-col">
      {/* Header */}
      <div className="flex items-center justify-between border-b bg-white px-6 py-4">
        <div className="flex items-center gap-4">
          <Button
            variant="ghost"
            size="icon"
            onClick={() =>
              navigate({
                to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId",
                params: { workspaceName, projectId, dashboardId: dashboardId || "" },
              })
            }
          >
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <div>
            <h1 className="comet-title-m">
              {isEditMode ? "Edit Chart" : "Create New Chart"}
            </h1>
            <p className="comet-body-s text-muted-foreground">
              Configure your chart and see a live preview
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <Button
            variant="outline"
            onClick={() =>
              navigate({
                to: "/$workspaceName/projects/$projectId/dashboards/$dashboardId",
                params: { workspaceName, projectId, dashboardId: dashboardId || "" },
              })
            }
          >
            Cancel
          </Button>
          <Button
            onClick={handleSave}
            disabled={!chartName || dataSeries.length === 0}
          >
            {isEditMode ? "Update Chart" : "Create Chart"}
          </Button>
        </div>
      </div>

      {/* Main Content - Split View */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left Panel - Configuration */}
        <div className="w-1/2 overflow-y-auto border-r bg-muted/30 p-6">
          <Tabs defaultValue="basic" className="w-full">
            <TabsList className="grid w-full grid-cols-3">
              <TabsTrigger value="basic">Basic Info</TabsTrigger>
              <TabsTrigger value="data">Data Series</TabsTrigger>
              <TabsTrigger value="grouping">Grouping</TabsTrigger>
            </TabsList>

            {/* Basic Info Tab */}
            <TabsContent value="basic" className="space-y-6">
              <Card>
                <CardContent className="space-y-4 pt-6">
                  <div className="space-y-2">
                    <Label htmlFor="name">
                      Chart Name <span className="text-red-500">*</span>
                    </Label>
                    <Input
                      id="name"
                      placeholder="e.g., Trace Count Over Time"
                      value={chartName}
                      onChange={(e) => setChartName(e.target.value)}
                    />
                  </div>

                  <div className="space-y-2">
                    <Label htmlFor="description">Description</Label>
                    <Textarea
                      id="description"
                      placeholder="Optional description for this chart"
                      rows={3}
                      value={chartDescription}
                      onChange={(e) => setChartDescription(e.target.value)}
                    />
                  </div>

                  <div className="space-y-2">
                    <Label>Chart Type</Label>
                    <div className="grid grid-cols-2 gap-4">
                      <Card
                        className={`cursor-pointer transition-all ${
                          chartType === "line"
                            ? "border-primary ring-2 ring-primary ring-offset-2"
                            : "hover:border-primary/50"
                        }`}
                        onClick={() => setChartType("line")}
                      >
                        <CardContent className="flex flex-col items-center justify-center p-6">
                          <LineChartIcon className="h-8 w-8 mb-2" />
                          <span className="font-medium">Line Chart</span>
                        </CardContent>
                      </Card>
                      <Card
                        className={`cursor-pointer transition-all ${
                          chartType === "bar"
                            ? "border-primary ring-2 ring-primary ring-offset-2"
                            : "hover:border-primary/50"
                        }`}
                        onClick={() => setChartType("bar")}
                      >
                        <CardContent className="flex flex-col items-center justify-center p-6">
                          <BarChart3 className="h-8 w-8 mb-2" />
                          <span className="font-medium">Bar Chart</span>
                        </CardContent>
                      </Card>
                    </div>
                  </div>
                </CardContent>
              </Card>
            </TabsContent>

            {/* Data Series Tab */}
            <TabsContent value="data" className="space-y-4">
              <div className="flex items-center justify-between">
                <div>
                  <h3 className="comet-body-m font-semibold">Data Series</h3>
                  <p className="comet-body-s text-muted-foreground">
                    Add multiple data series to compare metrics
                  </p>
                </div>
                <Button onClick={handleAddSeries} size="sm">
                  <Plus className="mr-1.5 h-4 w-4" />
                  Add Series
                </Button>
              </div>

              {dataSeries.length === 0 ? (
                <Card>
                  <CardContent className="flex flex-col items-center justify-center py-12 text-center">
                    <p className="text-muted-foreground mb-4">
                      No data series configured yet
                    </p>
                    <Button onClick={handleAddSeries} variant="outline">
                      <Plus className="mr-1.5 h-4 w-4" />
                      Add Your First Series
                    </Button>
                  </CardContent>
                </Card>
              ) : (
                <div className="space-y-4">
                  {dataSeries.map((series, index) => (
                    <Card key={index}>
                      <CardContent className="space-y-4 pt-6">
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <div
                              className="h-4 w-4 rounded-full"
                              style={{ backgroundColor: series.color }}
                            />
                            <span className="font-medium">Series {index + 1}</span>
                          </div>
                          <Button
                            variant="ghost"
                            size="icon"
                            onClick={() => handleRemoveSeries(index)}
                          >
                            <Trash2 className="h-4 w-4 text-red-500" />
                          </Button>
                        </div>

                        <Separator />

                        <div className="space-y-2">
                          <Label>Series Name</Label>
                          <Input
                            value={series.name}
                            onChange={(e) =>
                              handleUpdateSeries(index, { name: e.target.value })
                            }
                            placeholder="e.g., Production Traces"
                          />
                        </div>

                        <div className="space-y-2">
                          <Label>Project</Label>
                          <Select
                            value={series.project_id}
                            onValueChange={(value) =>
                              handleUpdateSeries(index, { project_id: value })
                            }
                          >
                            <SelectTrigger>
                              <SelectValue placeholder="Select project" />
                            </SelectTrigger>
                            <SelectContent>
                              {projects.map((project) => (
                                <SelectItem key={project.id} value={project.id}>
                                  {project.name}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>

                        <div className="space-y-2">
                          <Label>Metric</Label>
                          <Select
                            value={series.metric_type}
                            onValueChange={(value) =>
                              handleUpdateSeries(index, { metric_type: value })
                            }
                          >
                            <SelectTrigger>
                              <SelectValue placeholder="Select metric" />
                            </SelectTrigger>
                            <SelectContent>
                              {METRIC_OPTIONS.map((metric) => (
                                <SelectItem key={metric.value} value={metric.value}>
                                  {metric.label}
                                </SelectItem>
                              ))}
                            </SelectContent>
                          </Select>
                        </div>

                        <div className="space-y-2">
                          <Label>Color</Label>
                          <div className="flex gap-2">
                            <input
                              type="color"
                              value={series.color}
                              onChange={(e) =>
                                handleUpdateSeries(index, { color: e.target.value })
                              }
                              className="h-10 w-20 cursor-pointer rounded border"
                            />
                            <Input
                              value={series.color}
                              onChange={(e) =>
                                handleUpdateSeries(index, { color: e.target.value })
                              }
                              placeholder="#000000"
                              className="flex-1"
                            />
                          </div>
                        </div>
                      </CardContent>
                    </Card>
                  ))}
                </div>
              )}
            </TabsContent>

            {/* Grouping Tab */}
            <TabsContent value="grouping" className="space-y-4">
              <Card>
                <CardContent className="space-y-4 pt-6">
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="comet-body-m font-semibold">Group By</h3>
                      <p className="comet-body-s text-muted-foreground">
                        Split data into multiple series by a field
                      </p>
                    </div>
                    <span
                      className={`rounded-full px-3 py-1 text-xs font-medium ${
                        enableGrouping
                          ? "bg-primary text-primary-foreground"
                          : "border bg-background text-foreground"
                      }`}
                    >
                      {enableGrouping ? "Enabled" : "Disabled"}
                    </span>
                  </div>

                  <Separator />

                  <div className="space-y-4">
                    <div className="flex items-center gap-2">
                      <input
                        type="checkbox"
                        id="enable-grouping"
                        checked={enableGrouping}
                        onChange={(e) => setEnableGrouping(e.target.checked)}
                        className="h-4 w-4 rounded border-gray-300"
                      />
                      <Label htmlFor="enable-grouping" className="cursor-pointer">
                        Enable grouping
                      </Label>
                    </div>

                    {enableGrouping && (
                      <>
                        <div className="space-y-2">
                          <Label>Field</Label>
                          <Input
                            value={groupBy?.field || ""}
                            onChange={(e) =>
                              setGroupBy({
                                ...groupBy,
                                field: e.target.value,
                                type: groupBy?.type || "automatic",
                              } as GroupByConfig)
                            }
                            placeholder="e.g., user_id, model, environment"
                          />
                        </div>

                        <div className="space-y-2">
                          <Label>Type</Label>
                          <Select
                            value={groupBy?.type || "automatic"}
                            onValueChange={(value) =>
                              setGroupBy({
                                ...groupBy,
                                type: value as GroupByType,
                                field: groupBy?.field || "",
                              } as GroupByConfig)
                            }
                          >
                            <SelectTrigger>
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectItem value="automatic">Automatic</SelectItem>
                              <SelectItem value="manual">Manual</SelectItem>
                            </SelectContent>
                          </Select>
                        </div>

                        <div className="space-y-2">
                          <Label>Limit Top N</Label>
                          <Input
                            type="number"
                            min="1"
                            max="100"
                            value={groupBy?.limitTopN || 5}
                            onChange={(e) =>
                              setGroupBy({
                                ...groupBy,
                                limitTopN: parseInt(e.target.value) || 5,
                                field: groupBy?.field || "",
                                type: groupBy?.type || "automatic",
                              } as GroupByConfig)
                            }
                          />
                          <p className="text-xs text-muted-foreground">
                            Show only the top N groups (1-100)
                          </p>
                        </div>
                      </>
                    )}
                  </div>
                </CardContent>
              </Card>
            </TabsContent>
          </Tabs>
        </div>

        {/* Right Panel - Live Preview */}
        <div className="flex w-1/2 flex-col overflow-hidden bg-white">
          <div className="border-b px-6 py-4">
            <h2 className="comet-body-m font-semibold">Live Preview</h2>
            <p className="comet-body-s text-muted-foreground">
              See how your chart will look with the last 7 days of data
            </p>
          </div>

          <div className="flex-1 overflow-y-auto p-6">
            {dataSeries.length === 0 ? (
              <div className="flex h-full items-center justify-center text-center">
                <div>
                  <p className="text-lg font-medium text-muted-foreground">
                    No data configured
                  </p>
                  <p className="text-sm text-muted-foreground">
                    Add data series to see a preview of your chart
                  </p>
                </div>
              </div>
            ) : isLoadingPreview ? (
              <div className="flex h-full items-center justify-center">
                <Loader />
              </div>
            ) : previewError ? (
              <div className="flex h-full items-center justify-center text-center">
                <div>
                  <p className="text-lg font-medium text-red-500">
                    Failed to load preview
                  </p>
                  <p className="text-sm text-muted-foreground">
                    Check your configuration and try again
                  </p>
                </div>
              </div>
            ) : previewData ? (
              <ChartPreview
                data={previewData}
                chartType={chartType}
                dataSeries={dataSeries}
                isLoading={isLoadingPreview}
              />
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ChartConfigurationPage;

