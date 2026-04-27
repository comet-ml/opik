import React, { useCallback, useMemo, useState } from "react";
import { useNavigate } from "@tanstack/react-router";
import {
  Calendar,
  ChevronsRight,
  Clock,
  Coins,
  Copy,
  Download,
  ArrowUpRight,
  Hash,
  MessagesSquare,
  MoreHorizontal,
  Share,
  Trash,
} from "lucide-react";
import copy from "clipboard-copy";
import FileSaver from "file-saver";
import { json2csv } from "json-2-csv";
import get from "lodash/get";
import isBoolean from "lodash/isBoolean";
import isFunction from "lodash/isFunction";
import isUndefined from "lodash/isUndefined";
import uniq from "lodash/uniq";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_TYPE,
  OnChangeFn,
} from "@/types/shared";
import {
  mapRowDataForExport,
  TRACE_EXPORT_COLUMNS,
  THREAD_EXPORT_COLUMNS,
} from "@/lib/traces/exportUtils";
import { Trace } from "@/types/traces";
import { Filter } from "@/types/filters";
import { formatDate, formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import { manageToolFilter } from "@/v2/pages-shared/traces/spanTypeFilter";
import useAppStore from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/ui/button";
import { HotkeyDisplay } from "@/ui/hotkey-display";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useThreadById from "@/api/traces/useThreadById";
import useTracesList from "@/api/traces/useTracesList";
import useThreadBatchDeleteMutation from "@/api/traces/useThreadBatchDeleteMutation";
import TraceMessages from "@/v2/pages-shared/traces/TraceMessages/TraceMessages";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import {
  ButtonLayoutSize,
  DetailsActionSection,
  DetailsActionSectionToggle,
  useDetailsActionSectionState,
} from "@/v2/pages-shared/traces/DetailsActionSection";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { toast } from "@/ui/use-toast";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/ui/resizable";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Accordion } from "@/ui/accordion";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";
import ThreadAnnotatePanel from "./ThreadAnnotatePanel";
import useThreadFeedbackScoreDeleteMutation from "@/api/traces/useThreadFeedbackScoreDeleteMutation";
import ThreadFeedbackScoresInfo from "./ThreadFeedbackScoresInfo";
import { Separator } from "@/ui/separator";
import ThreadDetailsTags from "./ThreadDetailsTags";
import AddToDropdown from "@/v2/pages-shared/traces/AddToDropdown/AddToDropdown";
import ConfigurableFeedbackScoreTable from "../TraceDetailsPanel/TraceDataViewer/FeedbackScoreTable/ConfigurableFeedbackScoreTable";
import AttachmentsList from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import { MediaProvider } from "@/shared/PrettyLLMMessage/llmMessages";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { useThreadMedia } from "@/hooks/useThreadMedia";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { LOGS_TYPE } from "@/constants/traces";
import { useHotkeys } from "react-hotkeys-hook";

type ThreadDetailsPanelProps = {
  projectId: string;
  projectName: string;
  threadId: string;
  traceId?: string;
  setTraceId: OnChangeFn<string | null | undefined>;
  hasPreviousRow?: boolean;
  hasNextRow?: boolean;
  open: boolean;
  onClose: () => void;
  onRowChange?: (shift: number) => void;
};

const DEFAULT_TAB = "messages";

const ThreadDetailsPanel: React.FC<ThreadDetailsPanelProps> = ({
  projectId,
  projectName,
  threadId,
  traceId,
  setTraceId,
  hasPreviousRow,
  hasNextRow,
  open,
  onClose,
  onRowChange,
}) => {
  const navigate = useNavigate();

  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [popupOpen, setPopupOpen] = useState<boolean>(false);
  const [height, setHeight] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    const contentHeight = node.clientHeight;
    const headerHeight =
      node.querySelector('[data-panel-header="true"]')?.clientHeight || 0;
    const tabsHeight =
      node.querySelector('[data-panel-tabs="true"]')?.clientHeight || 0;
    const BOTTOM_PADDING = 16;
    setHeight(contentHeight - headerHeight - tabsHeight - BOTTOM_PADDING);
  });
  const [activeSection, setActiveSection] =
    useDetailsActionSectionState("lastThreadSection");

  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const { mutate: threadFeedbackScoreDelete } =
    useThreadFeedbackScoreDeleteMutation();
  const [activeTab = DEFAULT_TAB, setActiveTab] = useQueryParam(
    "threadTab",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [, setTracePanelFilters] = useQueryParam(
    `trace_panel_filters`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const { data: thread, isPending: isThreadPending } = useThreadById(
    {
      threadId,
      projectId,
    },
    {
      enabled: Boolean(threadId),
    },
  );
  const threadFeedbackScores = thread?.feedback_scores ?? [];
  const threadComments = thread?.comments ?? [];
  const threadTags = thread?.tags ?? [];

  const rows = useMemo(() => (thread ? [thread] : []), [thread]);

  const currentActiveTab = activeTab!;

  const currentActiveSection = activeSection;

  const { data: tracesData, isPending: isTracesPending } = useTracesList(
    {
      filters: [
        {
          id: "",
          field: "thread_id",
          type: COLUMN_TYPE.string,
          operator: "=",
          value: threadId,
        },
      ],
      projectId,
      page: 1,
      size: 1000,
      truncate: false,
    },
    {
      enabled: Boolean(threadId),
    },
  );

  const { mutate } = useThreadBatchDeleteMutation();
  const traces: Trace[] = useMemo(
    () =>
      (tracesData?.content ?? []).sort((t1, t2) => t1.id.localeCompare(t2.id)),
    [tracesData],
  );

  const { media } = useThreadMedia(traces);

  const handleOpenTrace = useCallback(
    (id: string, shouldFilterToolCalls: boolean) => {
      setTracePanelFilters((previousFilters: Filter[] | null | undefined) =>
        manageToolFilter(previousFilters, shouldFilterToolCalls),
      );

      onClose();
      setTraceId(id);
    },
    [setTracePanelFilters, setTraceId, onClose],
  );

  const handleThreadDelete = useCallback(() => {
    onClose();
    mutate({
      ids: [threadId],
      projectId,
    });
  }, [onClose, mutate, threadId, projectId]);

  const handleDeleteFeedbackScore = (name: string, author?: string) => {
    threadFeedbackScoreDelete({
      names: [name],
      threadId,
      projectName,
      projectId,
      author,
    });
  };

  const exportColumns = useMemo(() => {
    const feedbackScoreNames = uniq(
      (thread?.feedback_scores ?? []).map(
        (score) => `${COLUMN_FEEDBACK_SCORES_ID}.${score.name}`,
      ),
    );

    return [...THREAD_EXPORT_COLUMNS, ...feedbackScoreNames];
  }, [thread]);

  const traceExportColumns = useMemo(() => {
    const feedbackScoreNames = uniq(
      traces.reduce<string[]>((acc, trace) => {
        return acc.concat(
          (trace.feedback_scores ?? []).map(
            (score) => `${COLUMN_FEEDBACK_SCORES_ID}.${score.name}`,
          ),
        );
      }, []),
    );

    return [...TRACE_EXPORT_COLUMNS, ...feedbackScoreNames];
  }, [traces]);

  const handleExportCSV = useCallback(async () => {
    try {
      if (!thread) return;

      const mappedData = await mapRowDataForExport([thread], exportColumns);
      const mappedTraces = await mapRowDataForExport(
        traces,
        traceExportColumns,
      );

      const dataWithConversationHistory = [
        {
          ...mappedData[0],
          conversation_history: JSON.stringify(mappedTraces),
        },
      ];

      const csv = json2csv(dataWithConversationHistory);
      const fileName = `${threadId}-thread.csv`;
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" });
      FileSaver.saveAs(blob, fileName);

      toast({
        title: "Export successful",
        description: "Exported thread to CSV",
      });
    } catch (error) {
      toast({
        title: "Export failed",
        description: get(error, "message", "Failed to export"),
        variant: "destructive",
      });
    }
  }, [thread, threadId, exportColumns, traces, traceExportColumns]);

  const handleExportJSON = useCallback(async () => {
    try {
      if (!thread) return;

      const mappedThreadData = await mapRowDataForExport(
        [thread],
        exportColumns,
      );
      const mappedTraces = await mapRowDataForExport(
        traces,
        traceExportColumns,
      );

      const dataWithConversationHistory = {
        ...mappedThreadData[0],
        conversation_history: mappedTraces,
      };

      const fileName = `${threadId}-thread.json`;
      const blob = new Blob(
        [JSON.stringify(dataWithConversationHistory, null, 2)],
        {
          type: "application/json;charset=utf-8",
        },
      );
      FileSaver.saveAs(blob, fileName);

      toast({
        title: "Export successful",
        description: "Exported thread to JSON",
      });
    } catch (error) {
      toast({
        title: "Export failed",
        description: get(error, "message", "Failed to export"),
        variant: "destructive",
      });
    }
  }, [thread, threadId, exportColumns, traces, traceExportColumns]);

  const horizontalNavigation = useMemo(
    () =>
      isBoolean(hasNextRow) &&
      isBoolean(hasPreviousRow) &&
      isFunction(onRowChange)
        ? {
            onChange: onRowChange,
            hasNext: hasNextRow,
            hasPrevious: hasPreviousRow,
          }
        : undefined,
    [hasNextRow, hasPreviousRow, onRowChange],
  );

  useHotkeys(
    "a",
    (e) => {
      e.preventDefault();
      setActiveSection(DetailsActionSection.Annotate);
    },
    { enableOnFormTags: false, enabled: canAnnotateTraceSpanThread },
    [setActiveSection, canAnnotateTraceSpanThread],
  );
  useHotkeys(
    "j",
    () =>
      horizontalNavigation?.hasPrevious && horizontalNavigation.onChange(-1),
    { enabled: Boolean(horizontalNavigation) },
    [horizontalNavigation],
  );
  useHotkeys(
    "k",
    () => horizontalNavigation?.hasNext && horizontalNavigation.onChange(1),
    { enabled: Boolean(horizontalNavigation) },
    [horizontalNavigation],
  );

  const bodyStyle = {
    ...(height && { height: `${height}px` }),
  };

  const renderHeader = () => {
    if (isThreadPending) {
      return <Loader />;
    }

    return (
      <div className="flex flex-col gap-1">
        <div className=" flex w-full items-center gap-3 overflow-x-hidden py-1">
          <TooltipWrapper content="Thread start time">
            <div className="flex flex-nowrap items-center gap-x-1.5 px-1 text-muted-slate">
              <Calendar className="size-4 shrink-0" />
              <span className="comet-body-s truncate">
                {thread?.start_time ? formatDate(thread?.start_time) : "NA"}
              </span>
            </div>
          </TooltipWrapper>
          <TooltipWrapper content="Number of messages in the thread">
            <div className="flex flex-nowrap items-center gap-x-1.5 px-1 text-muted-slate">
              <Hash className="size-4 shrink-0" />
              <span className="comet-body-s truncate">
                {thread?.number_of_messages
                  ? `${thread.number_of_messages} messages`
                  : "NA"}
              </span>
            </div>
          </TooltipWrapper>
          <TooltipWrapper content="Thread duration">
            <div className="flex flex-nowrap items-center gap-x-1.5 px-1 text-muted-slate">
              <Clock className="size-4 shrink-0" />
              <span className="comet-body-s truncate">
                {formatDuration(thread?.duration, false)}
              </span>
            </div>
          </TooltipWrapper>
          {!isUndefined(thread?.total_estimated_cost) && (
            <TooltipWrapper
              content={`Estimated cost ${formatCost(
                thread?.total_estimated_cost,
                { modifier: "full" },
              )}`}
            >
              <div className="flex flex-nowrap items-center gap-x-1.5 px-1 text-muted-slate">
                <Coins className="size-4 shrink-0" />
                <span className="comet-body-s truncate">
                  {formatCost(thread?.total_estimated_cost)}
                </span>
              </div>
            </TooltipWrapper>
          )}
        </div>
        {thread && (
          <ThreadDetailsTags
            tags={threadTags}
            threadId={thread.thread_model_id}
            projectId={projectId}
          />
        )}
      </div>
    );
  };

  const renderBody = () => {
    if (isTracesPending) {
      return <Loader />;
    }

    return (
      <Tabs
        defaultValue="messages"
        value={currentActiveTab}
        onValueChange={setActiveTab}
      >
        <div className="mx-4" data-panel-tabs="true">
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="messages">
              Messages
            </TabsTrigger>
            <TabsTrigger variant="underline" value="feedback_scores">
              Feedback scores
            </TabsTrigger>
          </TabsList>
        </div>
        <TabsContent value="messages">
          <MediaProvider media={media}>
            {media.length > 0 && (
              <div className="mb-4 px-4">
                <Accordion type="multiple" defaultValue={["attachments"]}>
                  <AttachmentsList media={media} />
                </Accordion>
              </div>
            )}
            <div style={bodyStyle}>
              <TraceMessages
                traces={traces}
                handleOpenTrace={handleOpenTrace}
                traceId={traceId}
              />
            </div>
          </MediaProvider>
        </TabsContent>
        <TabsContent value="feedback_scores">
          <div style={bodyStyle} className="overflow-y-auto px-4">
            <ConfigurableFeedbackScoreTable
              onDeleteFeedbackScore={
                canAnnotateTraceSpanThread
                  ? handleDeleteFeedbackScore
                  : undefined
              }
              feedbackScores={threadFeedbackScores}
              onAddHumanReview={() =>
                setActiveSection(DetailsActionSection.Annotate)
              }
              entityType="thread"
            />
            {canAnnotateTraceSpanThread && (
              <ThreadFeedbackScoresInfo
                feedbackScores={threadFeedbackScores}
                onAddHumanReview={() =>
                  setActiveSection(DetailsActionSection.Annotate)
                }
              />
            )}
          </div>
        </TabsContent>
      </Tabs>
    );
  };

  const renderContent = () => {
    if (isThreadPending || isTracesPending) {
      return <Loader />;
    }

    if (!thread) {
      return <NoData />;
    }

    return (
      <div className="relative size-full">
        <ResizablePanelGroup direction="horizontal" autoSaveId="trace-sidebar">
          <ResizablePanel id="thread-viewer" defaultSize={70} minSize={50}>
            <div className="flex size-full flex-col">
              <div className="flex h-10 shrink-0 items-center gap-2 border-b bg-muted/50 px-4">
                <span className="comet-body-xs-accented whitespace-nowrap text-foreground">
                  Inspect
                </span>
                <div className="flex-auto" />
                <AddToDropdown
                  getDataForExport={async () => rows}
                  selectedRows={rows}
                  dataType="threads"
                  buttonVariant="ghost"
                  buttonSize="2xs"
                />
                {canAnnotateTraceSpanThread && (
                  <DetailsActionSectionToggle
                    activeSection={null}
                    setActiveSection={setActiveSection}
                    layoutSize={ButtonLayoutSize.Large}
                    type={DetailsActionSection.Annotate}
                    variant="ghost"
                    buttonSize="2xs"
                    hotkey="A"
                  />
                )}
              </div>
              <div ref={ref} className="relative min-h-0 flex-auto">
                <div className="px-4 pb-6 pt-4" data-panel-header="true">
                  {renderHeader()}
                </div>
                <div data-panel-body="true">{renderBody()}</div>
              </div>
            </div>
          </ResizablePanel>
          {Boolean(currentActiveSection) && canAnnotateTraceSpanThread && (
            <>
              <ResizableHandle />
              <ResizablePanel
                id="thread-last-section-viewer"
                defaultSize={30}
                minSize={30}
              >
                {currentActiveSection === DetailsActionSection.Annotate && (
                  <ThreadAnnotatePanel
                    threadId={threadId}
                    projectId={projectId}
                    projectName={projectName}
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
                    feedbackScores={threadFeedbackScores}
                    comments={threadComments}
                  />
                )}
              </ResizablePanel>
            </>
          )}
        </ResizablePanelGroup>
      </div>
    );
  };

  const renderHeaderContent = () => {
    return (
      <div className="flex flex-auto items-center justify-between">
        <div className="flex items-center gap-1 overflow-hidden">
          <TooltipWrapper content="Close panel">
            <Button variant="ghost" size="icon-2xs" onClick={onClose}>
              <ChevronsRight />
            </Button>
          </TooltipWrapper>
          <div className="relative flex size-4 shrink-0 items-center justify-center rounded bg-[var(--thread-icon-background)] text-[var(--thread-icon-text)]">
            <MessagesSquare className="size-2" />
          </div>
          <span className="comet-body-s-accented truncate">Thread</span>
        </div>

        <div className="flex shrink-0 items-center gap-2 pl-4">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="icon-2xs">
                <span className="sr-only">Actions menu</span>
                <MoreHorizontal />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-52">
              <DropdownMenuItem
                onClick={() => {
                  toast({
                    description: "URL successfully copied to clipboard",
                  });
                  copy(window.location.href);
                }}
              >
                <Share className="mr-2 size-4" />
                Share
              </DropdownMenuItem>
              <TooltipWrapper content={threadId} side="left">
                <DropdownMenuItem
                  onClick={() => {
                    toast({
                      description: `Thread ID successfully copied to clipboard`,
                    });
                    copy(threadId);
                  }}
                >
                  <Copy className="mr-2 size-4" />
                  Copy thread ID
                </DropdownMenuItem>
              </TooltipWrapper>
              <DropdownMenuSeparator />
              {(["csv", "json"] as const).map((format) => {
                const handler =
                  format === "csv" ? handleExportCSV : handleExportJSON;
                const item = (
                  <DropdownMenuItem
                    key={format}
                    onClick={handler}
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
              <DropdownMenuSeparator />
              <DropdownMenuItem
                onClick={() => setPopupOpen(true)}
                variant="destructive"
              >
                <Trash className="mr-2 size-4" />
                Delete
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <ConfirmDialog
            open={popupOpen}
            setOpen={setPopupOpen}
            onConfirm={handleThreadDelete}
            title="Delete thread"
            description="Deleting a thread will also remove all traces linked to it and their data. This action can’t be undone. Are you sure you want to continue?"
            confirmText="Delete thread"
            confirmButtonVariant="destructive"
          />

          {horizontalNavigation && (
            <>
              <Separator orientation="vertical" className="mx-1 h-4" />
              <Button
                variant="outline"
                size="2xs"
                disabled={!horizontalNavigation.hasPrevious}
                onClick={() => horizontalNavigation.onChange(-1)}
                className="gap-1"
              >
                Previous
                <HotkeyDisplay hotkey="J" variant="outline" size="2xs" />
              </Button>
              <Button
                variant="outline"
                size="2xs"
                disabled={!horizontalNavigation.hasNext}
                onClick={() => horizontalNavigation.onChange(1)}
                className="gap-1"
              >
                Next
                <HotkeyDisplay hotkey="K" variant="outline" size="2xs" />
              </Button>
            </>
          )}

          <TooltipWrapper content="View all traces for this thread">
            <Button
              variant="outline"
              size="2xs"
              onClick={() => {
                navigate({
                  to: "/$workspaceName/projects/$projectId/logs",
                  params: {
                    projectId,
                    workspaceName,
                  },
                  search: {
                    logsType: LOGS_TYPE.traces,
                    traces_filters: [
                      {
                        id: "thread_id_filter",
                        field: "thread_id",
                        type: COLUMN_TYPE.string,
                        operator: "=",
                        value: threadId,
                      },
                    ],
                  },
                });
              }}
            >
              Traces
              <ArrowUpRight className="ml-1 size-3.5" />
            </Button>
          </TooltipWrapper>
        </div>
      </div>
    );
  };

  return (
    <ResizableSidePanel
      panelId="thread"
      entity="thread"
      open={open}
      headerContent={renderHeaderContent()}
      onClose={onClose}
      hideDefaultControls
      initialWidth={0.5}
      minWidth={700}
      horizontalNavigation={horizontalNavigation}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default ThreadDetailsPanel;
