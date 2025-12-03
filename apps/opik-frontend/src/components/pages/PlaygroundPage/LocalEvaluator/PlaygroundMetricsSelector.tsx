import React, { useMemo, useState, useCallback } from "react";
import {
  ChevronDown,
  Pencil,
  Plus,
  Settings,
  Trash2,
} from "lucide-react";
import toLower from "lodash/toLower";

import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Separator } from "@/components/ui/separator";
import { Checkbox } from "@/components/ui/checkbox";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { PlaygroundMetricConfig } from "@/types/local-evaluator";
import useLocalEvaluatorMetrics from "@/api/local-evaluator/useLocalEvaluatorMetrics";
import useLocalEvaluatorHealthcheck from "@/api/local-evaluator/useLocalEvaluatorHealthcheck";
import {
  useLocalEvaluatorEnabled,
  useLocalEvaluatorUrl,
  usePlaygroundMetrics,
  useAddPlaygroundMetric,
  useUpdatePlaygroundMetric,
  useDeletePlaygroundMetric,
  useSelectedPlaygroundMetricIds,
  useSetSelectedPlaygroundMetricIds,
} from "@/store/PlaygroundStore";
import AddPlaygroundMetricDialog from "./AddPlaygroundMetricDialog";
import LocalEvaluatorConfigDialog from "./LocalEvaluatorConfigDialog";

interface PlaygroundMetricsSelectorProps {
  projectId?: string;
  projectName?: string;
  datasetColumnNames?: string[];
}

const PlaygroundMetricsSelector: React.FC<PlaygroundMetricsSelectorProps> = ({
  projectId,
  projectName,
  datasetColumnNames,
}) => {
  const [open, setOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [configDialogOpen, setConfigDialogOpen] = useState(false);
  const [metricDialogOpen, setMetricDialogOpen] = useState(false);
  const [editingMetric, setEditingMetric] = useState<
    PlaygroundMetricConfig | undefined
  >();

  const enabled = useLocalEvaluatorEnabled();
  const url = useLocalEvaluatorUrl();
  const playgroundMetrics = usePlaygroundMetrics();
  const addPlaygroundMetric = useAddPlaygroundMetric();
  const updatePlaygroundMetric = useUpdatePlaygroundMetric();
  const deletePlaygroundMetric = useDeletePlaygroundMetric();
  const selectedMetricIds = useSelectedPlaygroundMetricIds();
  const setSelectedMetricIds = useSetSelectedPlaygroundMetricIds();

  const { data: isHealthy } = useLocalEvaluatorHealthcheck(
    { url },
    { enabled: enabled && !!url },
  );

  const { data: metricsData } = useLocalEvaluatorMetrics(
    { url },
    { enabled: enabled && isHealthy === true },
  );

  const availableMetrics = metricsData?.metrics || [];

  // Helper to get display name (custom name or metric class name)
  const getDisplayName = useCallback(
    (metric: PlaygroundMetricConfig) => metric.name || metric.metric_name,
    [],
  );

  const filteredMetrics = useMemo(() => {
    if (!search) return playgroundMetrics;
    const searchLower = toLower(search);
    return playgroundMetrics.filter(
      (m) =>
        toLower(getDisplayName(m)).includes(searchLower) ||
        toLower(m.metric_name).includes(searchLower),
    );
  }, [playgroundMetrics, search, getDisplayName]);

  const isAllSelected =
    selectedMetricIds === null ||
    (selectedMetricIds.length === playgroundMetrics.length &&
      playgroundMetrics.length > 0);

  const handleSelect = useCallback(
    (metricId: string) => {
      if (
        selectedMetricIds === null ||
        selectedMetricIds.length === playgroundMetrics.length
      ) {
        const allMetricIds = playgroundMetrics.map((m) => m.id);
        const newSelection = allMetricIds.filter((id) => id !== metricId);
        setSelectedMetricIds(newSelection.length > 0 ? newSelection : []);
      } else {
        const isSelected = selectedMetricIds.includes(metricId);
        if (isSelected) {
          const newSelection = selectedMetricIds.filter((id) => id !== metricId);
          setSelectedMetricIds(newSelection.length > 0 ? newSelection : []);
        } else {
          const newSelection = [...selectedMetricIds, metricId];
          setSelectedMetricIds(
            newSelection.length === playgroundMetrics.length
              ? null
              : newSelection,
          );
        }
      }
    },
    [selectedMetricIds, playgroundMetrics, setSelectedMetricIds],
  );

  const handleSelectAll = useCallback(() => {
    if (isAllSelected) {
      setSelectedMetricIds([]);
    } else {
      setSelectedMetricIds(null);
    }
  }, [isAllSelected, setSelectedMetricIds]);

  const isSelected = useCallback(
    (metricId: string) => {
      if (isAllSelected) return true;
      return selectedMetricIds?.includes(metricId) || false;
    },
    [isAllSelected, selectedMetricIds],
  );

  const displayValue = useMemo(() => {
    if (!enabled) return "Local metrics";
    if (!isHealthy) return "Server offline";
    if (playgroundMetrics.length === 0) return "No metrics";

    const selectedCount =
      selectedMetricIds === null
        ? playgroundMetrics.length
        : selectedMetricIds.length;

    if (selectedCount === 0) return "No metrics selected";
    if (selectedCount === 1) {
      const selectedMetric =
        selectedMetricIds === null
          ? playgroundMetrics[0]
          : playgroundMetrics.find((m) => m.id === selectedMetricIds[0]);
      return selectedMetric ? getDisplayName(selectedMetric) : "1 metric";
    }
    return `${selectedCount} metrics`;
  }, [enabled, isHealthy, playgroundMetrics, selectedMetricIds, getDisplayName]);

  const tooltipContent = useMemo(() => {
    if (!enabled)
      return "Enable local evaluator to run Python metrics from your eval_app server";
    if (!isHealthy)
      return "Local eval_app server is not running. Start it with: opik eval-app";
    if (playgroundMetrics.length === 0) return "Add a metric to evaluate traces";
    if (
      selectedMetricIds === null ||
      selectedMetricIds.length === playgroundMetrics.length
    ) {
      return `All ${playgroundMetrics.length} metrics selected`;
    }
    return playgroundMetrics
      .filter((m) => selectedMetricIds?.includes(m.id))
      .map((m) => getDisplayName(m))
      .join(", ");
  }, [enabled, isHealthy, playgroundMetrics, selectedMetricIds, getDisplayName]);

  const handleSaveMetric = useCallback(
    (metric: PlaygroundMetricConfig) => {
      if (editingMetric) {
        updatePlaygroundMetric(metric.id, metric);
      } else {
        addPlaygroundMetric(metric);
        // Auto-select the new metric
        if (selectedMetricIds === null) {
          // All were selected, keep it that way
        } else {
          setSelectedMetricIds([...selectedMetricIds, metric.id]);
        }
      }
      setEditingMetric(undefined);
    },
    [
      editingMetric,
      addPlaygroundMetric,
      updatePlaygroundMetric,
      selectedMetricIds,
      setSelectedMetricIds,
    ],
  );

  const handleEditMetric = useCallback((metric: PlaygroundMetricConfig) => {
    setEditingMetric(metric);
    setMetricDialogOpen(true);
    setOpen(false);
  }, []);

  const handleDeleteMetric = useCallback(
    (metricId: string) => {
      deletePlaygroundMetric(metricId);
    },
    [deletePlaygroundMetric],
  );

  const handleAddMetric = useCallback(() => {
    setEditingMetric(undefined);
    setMetricDialogOpen(true);
    setOpen(false);
  }, []);

  const hasNoMetrics = playgroundMetrics.length === 0;
  const isServerReady = enabled && isHealthy;

  return (
    <>
      <Popover open={open} onOpenChange={setOpen}>
        <TooltipWrapper content={tooltipContent}>
          <PopoverTrigger asChild>
            <Button
              className={cn("group w-[200px] justify-between", {
                "text-muted-foreground": !enabled,
                "text-destructive": enabled && !isHealthy,
              })}
              size="sm"
              variant="outline"
              type="button"
            >
              <div className="flex min-w-0 flex-1 items-center">
                <span
                  className={cn("mr-2 size-2 shrink-0 rounded-full", {
                    "bg-muted-foreground": !enabled,
                    "bg-destructive": enabled && !isHealthy,
                    "bg-green-500": enabled && isHealthy,
                  })}
                />
                <span className="truncate">{displayValue}</span>
              </div>
              <ChevronDown className="ml-2 size-4 shrink-0 text-light-slate" />
            </Button>
          </PopoverTrigger>
        </TooltipWrapper>
        <PopoverContent
          align="start"
          style={{ width: "300px" }}
          className="relative p-1 pt-12"
          hideWhenDetached
          onCloseAutoFocus={(e) => e.preventDefault()}
        >
          <div className="absolute inset-x-1 top-0 h-12">
            <SearchInput
              searchText={search}
              setSearchText={setSearch}
              placeholder="Search metrics"
              variant="ghost"
            />
            <Separator className="mt-1" />
          </div>
          <div className="max-h-[40vh] overflow-y-auto overflow-x-hidden">
            {hasNoMetrics ? (
              <div className="flex min-h-[120px] flex-col items-center justify-center px-4 py-2 text-center">
                <div className="comet-body-s-accented pb-1 text-foreground">
                  No metrics configured
                </div>
                <div className="comet-body-s text-muted-slate">
                  Add a metric to evaluate your playground traces with Python
                  metrics from your local eval_app server.
                </div>
              </div>
            ) : filteredMetrics.length > 0 ? (
              <>
                {filteredMetrics.map((metric) => (
                  <div
                    key={metric.id}
                    className="group flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                    onClick={() => handleSelect(metric.id)}
                  >
                    <Checkbox
                      checked={isSelected(metric.id)}
                      className="shrink-0"
                    />
                    <div className="min-w-0 flex-1">
                      <div className="comet-body-s truncate">
                        {getDisplayName(metric)}
                      </div>
                      {metric.name && (
                        <div className="comet-body-xs truncate text-muted-slate">
                          {metric.metric_name}
                        </div>
                      )}
                    </div>
                    <div
                      className="flex shrink-0 items-center gap-1 opacity-0 group-hover:opacity-100"
                      onClick={(e) => e.stopPropagation()}
                    >
                      <Button
                        variant="ghost"
                        size="icon-xs"
                        onClick={() => handleEditMetric(metric)}
                      >
                        <Pencil className="size-3" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon-xs"
                        onClick={() => handleDeleteMetric(metric.id)}
                      >
                        <Trash2 className="size-3" />
                      </Button>
                    </div>
                  </div>
                ))}
              </>
            ) : (
              <div className="flex h-20 items-center justify-center text-muted-foreground">
                No metrics found
              </div>
            )}
          </div>

          <div className="sticky inset-x-0 bottom-0">
            {!hasNoMetrics && filteredMetrics.length > 0 && (
              <>
                <Separator className="my-1" />
                <div
                  className="flex h-10 cursor-pointer items-center gap-2 rounded-md px-4 hover:bg-primary-foreground"
                  onClick={handleSelectAll}
                >
                  <Checkbox checked={isAllSelected} className="shrink-0" />
                  <div className="min-w-0 flex-1">
                    <div className="comet-body-s truncate">Select all</div>
                  </div>
                </div>
              </>
            )}

            <Separator className="my-1" />
            <TooltipWrapper
              content={
                !isServerReady
                  ? "Start the local eval_app server to add metrics"
                  : undefined
              }
            >
              <div
                className={cn(
                  "flex h-10 items-center rounded-md px-4",
                  isServerReady
                    ? "cursor-pointer hover:bg-primary-foreground"
                    : "cursor-not-allowed opacity-50",
                )}
                onClick={isServerReady ? handleAddMetric : undefined}
              >
                <div className="comet-body-s flex items-center gap-2 text-primary">
                  <Plus className="size-3.5 shrink-0" />
                  <span>Add metric</span>
                </div>
              </div>
            </TooltipWrapper>

            <Separator className="my-1" />
            <div
              className="flex h-10 cursor-pointer items-center rounded-md px-4 hover:bg-primary-foreground"
              onClick={() => {
                setOpen(false);
                setConfigDialogOpen(true);
              }}
            >
              <div className="comet-body-s flex items-center gap-2 text-muted-slate">
                <Settings className="size-3.5 shrink-0" />
                <span>Settings</span>
              </div>
            </div>
          </div>
        </PopoverContent>
      </Popover>

      <AddPlaygroundMetricDialog
        open={metricDialogOpen}
        setOpen={setMetricDialogOpen}
        metric={editingMetric}
        metrics={availableMetrics}
        onSave={handleSaveMetric}
        projectId={projectId}
        projectName={projectName}
        datasetColumnNames={datasetColumnNames}
      />

      <LocalEvaluatorConfigDialog
        open={configDialogOpen}
        setOpen={setConfigDialogOpen}
      />
    </>
  );
};

export default PlaygroundMetricsSelector;

