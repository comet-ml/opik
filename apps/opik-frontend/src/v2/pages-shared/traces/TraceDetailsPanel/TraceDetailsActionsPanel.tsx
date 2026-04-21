import React, { useCallback, useMemo, useState } from "react";
import copy from "clipboard-copy";
import FileSaver from "file-saver";
import { json2csv } from "json-2-csv";
import get from "lodash/get";
import {
  ArrowUpRight,
  ChevronsRight,
  Copy,
  Download,
  MoreHorizontal,
  Share,
  Sparkles,
  Trash,
} from "lucide-react";
import uniq from "lodash/uniq";
import isArray from "lodash/isArray";

import { useNavigate } from "@tanstack/react-router";

import { COLUMN_FEEDBACK_SCORES_ID, OnChangeFn } from "@/types/shared";
import { BASE_TRACE_DATA_TYPE, Span, Trace } from "@/types/traces";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useTraceDeleteMutation from "@/api/traces/useTraceDeleteMutation";
import { useToast } from "@/ui/use-toast";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import BaseTraceDataTypeIcon from "@/shared/BaseTraceDataTypeIcon/BaseTraceDataTypeIcon";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  DetailsActionSection,
  DetailsActionSectionValue,
} from "@/v2/pages-shared/traces/DetailsActionSection";
import {
  mapRowDataForExport,
  TRACE_EXPORT_COLUMNS,
} from "@/lib/traces/exportUtils";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { usePermissions } from "@/contexts/PermissionsContext";
import { useHotkeys } from "react-hotkeys-hook";

type ArrowNavigationConfig = {
  hasPrevious: boolean;
  hasNext: boolean;
  onChange: (shift: 1 | -1) => void;
  previousTooltip?: string;
  nextTooltip?: string;
};

type TraceDetailsActionsPanelProps = {
  projectId: string;
  traceId: string;
  spanId: string;
  traceName?: string;
  traceType?: BASE_TRACE_DATA_TYPE;
  threadId?: string;
  setThreadId?: OnChangeFn<string | null | undefined>;
  onDelete: () => void;
  onClose: () => void;
  treeData: Array<Trace | Span>;
  setActiveSection: (v: DetailsActionSectionValue) => void;
  horizontalNavigation?: ArrowNavigationConfig;
};

const TraceDetailsActionsPanel: React.FunctionComponent<
  TraceDetailsActionsPanelProps
> = ({
  projectId,
  traceId,
  spanId,
  traceName,
  traceType,
  threadId,
  setThreadId,
  onDelete,
  onClose,
  treeData,
  setActiveSection,
  horizontalNavigation,
}) => {
  const [popupOpen, setPopupOpen] = useState(false);
  const isAIInspectorEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.TOGGLE_OPIK_AI_ENABLED,
  );
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const {
    permissions: { canDeleteTraces, canViewExperiments },
  } = usePermissions();

  const { toast } = useToast();
  const { mutate } = useTraceDeleteMutation();

  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();

  const hasThread = Boolean(setThreadId && threadId);
  const experiment = useMemo(() => {
    const node = treeData.find((item) => item.id === traceId);
    return node && "experiment" in node ? node.experiment : undefined;
  }, [treeData, traceId]);
  const canNavigateToExperiment =
    Boolean(experiment) && canViewExperiments && Boolean(activeProjectId);

  useHotkeys(
    "j",
    () =>
      horizontalNavigation?.hasPrevious && horizontalNavigation.onChange(-1),
    { enabled: Boolean(horizontalNavigation), enableOnFormTags: false },
    [horizontalNavigation],
  );
  useHotkeys(
    "k",
    () => horizontalNavigation?.hasNext && horizontalNavigation.onChange(1),
    { enabled: Boolean(horizontalNavigation), enableOnFormTags: false },
    [horizontalNavigation],
  );

  const handleTraceDelete = useCallback(() => {
    onDelete();
    mutate({ traceId, projectId });
  }, [onDelete, mutate, traceId, projectId]);

  const getDataToExport = useCallback(
    (items: Array<Trace | Span>) => {
      const collectDescendants = (rootId: string): Array<Span> => {
        const result: Array<Span> = [];
        const queue = [rootId];
        while (queue.length > 0) {
          const parentId = queue.shift()!;
          for (const item of items) {
            if ("parent_span_id" in item && item.parent_span_id === parentId) {
              result.push(item as Span);
              queue.push(item.id);
            }
          }
        }
        return result;
      };

      if (spanId) {
        const span = items.find((item) => item.id === spanId);
        const dataToExport = span ? [span, ...collectDescendants(spanId)] : [];
        return {
          dataToExport,
          entityType: TRACE_DATA_TYPE.spans,
          entityId: spanId,
        };
      }

      const trace = items.find((item) => item.id === traceId);
      const allSpans = items.filter(
        (item): item is Span => "trace_id" in item && item.trace_id === traceId,
      );
      return {
        dataToExport: trace ? [trace, ...allSpans] : [],
        entityType: TRACE_DATA_TYPE.traces,
        entityId: traceId,
      };
    },
    [spanId, traceId],
  );

  const exportColumns = useMemo(() => {
    const feedbackScoreNames = uniq(
      treeData.reduce<string[]>((acc, d) => {
        return acc.concat(
          isArray(d.feedback_scores)
            ? d.feedback_scores.map(
                (score) => `${COLUMN_FEEDBACK_SCORES_ID}.${score.name}`,
              )
            : [],
        );
      }, []),
    );
    return [...TRACE_EXPORT_COLUMNS, ...feedbackScoreNames];
  }, [treeData]);

  const handleExport = useCallback(
    async (format: "csv" | "json") => {
      try {
        const { dataToExport, entityType, entityId } =
          getDataToExport(treeData);
        const mappedData = await mapRowDataForExport(
          dataToExport,
          exportColumns,
        );
        const fileSuffix =
          entityType === TRACE_DATA_TYPE.spans ? "span" : "trace";
        const fileName = `${entityId}-${fileSuffix}.${format}`;

        const blob =
          format === "csv"
            ? new Blob([json2csv(mappedData)], {
                type: "text/csv;charset=utf-8",
              })
            : new Blob([JSON.stringify(mappedData, null, 2)], {
                type: "application/json;charset=utf-8",
              });

        FileSaver.saveAs(blob, fileName);
        toast({
          title: "Export successful",
          description: `Exported ${fileSuffix} to ${format.toUpperCase()}`,
        });
      } catch (error) {
        toast({
          title: "Export failed",
          description: get(error, "message", "Failed to export"),
          variant: "destructive",
        });
      }
    },
    [treeData, exportColumns, getDataToExport, toast],
  );

  return (
    <div className="flex flex-auto items-center justify-between">
      <div className="flex items-center gap-1 overflow-hidden">
        <TooltipWrapper content="Close panel">
          <Button variant="ghost" size="icon-xs" onClick={onClose}>
            <ChevronsRight />
          </Button>
        </TooltipWrapper>
        {traceType && <BaseTraceDataTypeIcon type={traceType} />}
        <span className="comet-body-s-accented truncate">{traceName}</span>
      </div>

      <div className="flex shrink-0 items-center gap-2 pl-4">
        {isAIInspectorEnabled && (
          <TooltipWrapper content="Debug your trace with AI assistance (OpikAssist)">
            <Button
              variant="outline"
              size="xs"
              onClick={() =>
                setActiveSection(DetailsActionSection.AIAssistants)
              }
            >
              <Sparkles className="size-3.5 shrink-0" />
              <span className="ml-1.5">Improve with Ollie</span>
            </Button>
          </TooltipWrapper>
        )}

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="icon-xs">
              <span className="sr-only">Actions menu</span>
              <MoreHorizontal />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-52">
            <DropdownMenuItem
              onClick={() => {
                toast({ description: "URL copied to clipboard" });
                copy(window.location.href);
              }}
            >
              <Share className="mr-2 size-4" />
              Share
            </DropdownMenuItem>
            <TooltipWrapper content={traceId} side="left">
              <DropdownMenuItem
                onClick={() => {
                  toast({ description: "Trace ID copied to clipboard" });
                  copy(traceId);
                }}
              >
                <Copy className="mr-2 size-4" />
                Copy trace ID
              </DropdownMenuItem>
            </TooltipWrapper>
            {spanId && (
              <TooltipWrapper content={spanId} side="left">
                <DropdownMenuItem
                  onClick={() => {
                    toast({ description: "Span ID copied to clipboard" });
                    copy(spanId);
                  }}
                >
                  <Copy className="mr-2 size-4" />
                  Copy span ID
                </DropdownMenuItem>
              </TooltipWrapper>
            )}
            <DropdownMenuSeparator />
            {(["csv", "json"] as const).map((format) => {
              const item = (
                <DropdownMenuItem
                  key={format}
                  onClick={() => handleExport(format)}
                  disabled={!isExportEnabled}
                >
                  <Download className="mr-2 size-4" />
                  Export as {format.toUpperCase()}
                </DropdownMenuItem>
              );

              return isExportEnabled ? (
                item
              ) : (
                <TooltipWrapper
                  key={format}
                  content="Export functionality is disabled for this installation"
                  side="left"
                >
                  <div>{item}</div>
                </TooltipWrapper>
              );
            })}
            {canDeleteTraces && (
              <>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onClick={() => setPopupOpen(true)}
                  variant="destructive"
                >
                  <Trash className="mr-2 size-4" />
                  Delete trace
                </DropdownMenuItem>
              </>
            )}
          </DropdownMenuContent>
        </DropdownMenu>

        {canDeleteTraces && (
          <ConfirmDialog
            open={popupOpen}
            setOpen={setPopupOpen}
            onConfirm={handleTraceDelete}
            title="Delete trace"
            description="Deleting a trace will also remove the trace data from related experiment samples. This action can't be undone. Are you sure you want to continue?"
            confirmText="Delete trace"
            confirmButtonVariant="destructive"
          />
        )}

        {horizontalNavigation && (
          <>
            <Separator orientation="vertical" className="mx-1 h-4" />
            <Button
              variant="outline"
              size="xs"
              disabled={!horizontalNavigation.hasPrevious}
              onClick={() => horizontalNavigation.onChange(-1)}
              className="gap-2"
            >
              Previous
              <kbd className="flex h-5 min-w-5 items-center justify-center rounded-sm border px-1 text-xs text-muted-foreground">
                J
              </kbd>
            </Button>
            <Button
              variant="outline"
              size="xs"
              disabled={!horizontalNavigation.hasNext}
              onClick={() => horizontalNavigation.onChange(1)}
              className="gap-2"
            >
              Next
              <kbd className="flex h-5 min-w-5 items-center justify-center rounded-sm border px-1 text-xs text-muted-foreground">
                K
              </kbd>
            </Button>
          </>
        )}

        {canNavigateToExperiment && experiment && (
          <TooltipWrapper
            content={`View this item in experiment: ${experiment.name}`}
          >
            <Button
              variant="outline"
              size="xs"
              onClick={() =>
                navigate({
                  to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
                  params: {
                    workspaceName,
                    projectId: activeProjectId as string,
                    datasetId: experiment.dataset_id,
                  },
                  search: {
                    experiments: [experiment.id],
                    row: experiment.dataset_item_id,
                  },
                })
              }
            >
              Experiment
              <ArrowUpRight className="ml-1 size-3.5" />
            </Button>
          </TooltipWrapper>
        )}

        {hasThread && (
          <TooltipWrapper content="Go to thread">
            <Button
              variant="outline"
              size="xs"
              onClick={() => setThreadId!(threadId)}
            >
              Thread
              <ArrowUpRight className="ml-1 size-3.5" />
            </Button>
          </TooltipWrapper>
        )}
      </div>
    </div>
  );
};

export default TraceDetailsActionsPanel;
