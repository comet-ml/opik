import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import {
  Calendar,
  ChevronDown,
  Clock,
  Copy,
  Hash,
  MessageCircleMore,
  MessageCircleOff,
  MessagesSquare,
  MoreHorizontal,
  Share,
  Trash,
} from "lucide-react";
import copy from "clipboard-copy";
import isBoolean from "lodash/isBoolean";
import isFunction from "lodash/isFunction";

import { COLUMN_TYPE, OnChangeFn } from "@/types/shared";
import { Trace } from "@/types/traces";
import { formatDate, formatDuration } from "@/lib/date";
import useAppStore from "@/store/AppStore";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import Loader from "@/components/shared/Loader/Loader";
import NoData from "@/components/shared/NoData/NoData";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import useThreadById from "@/api/traces/useThreadById";
import useTracesList from "@/api/traces/useTracesList";
import useThreadBatchDeleteMutation from "@/api/traces/useThreadBatchDeleteMutation";
import TraceMessages from "@/components/pages-shared/traces/ThreadDetailsPanel/TraceMessages";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import {
  ButtonLayoutSize,
  DetailsActionSection,
  DetailsActionSectionToggle,
  useDetailsActionSectionState,
} from "@/components/pages-shared/traces/DetailsActionSection";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { toast } from "@/components/ui/use-toast";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import ThreadComments from "./ThreadComments";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { StringParam, useQueryParam } from "use-query-params";
import ThreadAnnotations from "./ThreadAnnotations";
import FeedbackScoreTab from "../TraceDetailsPanel/TraceDataViewer/FeedbackScoreTab";
import SetInactiveConfirmDialog from "./SetInactiveConfirmDialog";
import ThreadStatusTag from "@/components/shared/ThreadStatusTag/ThreadStatusTag";
import { ThreadStatus } from "@/types/thread";
import useThreadFeedbackScoreDeleteMutation from "@/api/traces/useThreadFeedbackScoreDeleteMutation";
import ThreadFeedbackScoresInfo from "./ThreadFeedbackScoresInfo";
import { Separator } from "@/components/ui/separator";
import ThreadDetailsTags from "./ThreadDetailsTags";

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
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [popupOpen, setPopupOpen] = useState<boolean>(false);
  const [setInactiveOpen, changeSetInactiveOpen] = useState<boolean>(false);
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

  const { mutate: threadFeedbackScoreDelete } =
    useThreadFeedbackScoreDeleteMutation();
  const [activeTab = DEFAULT_TAB, setActiveTab] = useQueryParam(
    "threadTab",
    StringParam,
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
      placeholderData: keepPreviousData,
      enabled: Boolean(threadId),
    },
  );
  const isInactiveThread = thread?.status === ThreadStatus.INACTIVE;
  const threadFeedbackScores = thread?.feedback_scores ?? [];
  const threadComments = thread?.comments ?? [];
  const threadTags = thread?.tags ?? [];

  let currentActiveTab = activeTab!;
  if (activeTab === "feedback_scores" && !isInactiveThread) {
    currentActiveTab = DEFAULT_TAB;
  }

  let currentActiveSection = activeSection;
  if (!isInactiveThread) {
    currentActiveSection = null;
  }

  const disabledAnnotationExplainer = !isInactiveThread
    ? "Feedback scores are disabled during an ongoing session to avoid conflicts while the thread is still active. "
    : "";
  const disabledCommentsExplainer = !isInactiveThread
    ? "Comments are disabled during an ongoing session to avoid conflicts while the thread is still active. "
    : "";

  const annotationCount = threadFeedbackScores.length;
  const commentsCount = threadComments.length;

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
      truncate: true,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(threadId),
    },
  );

  const { mutate } = useThreadBatchDeleteMutation();
  const traces: Trace[] = useMemo(
    () =>
      (tracesData?.content ?? []).sort((t1, t2) => t1.id.localeCompare(t2.id)),
    [tracesData],
  );

  const handleOpenTrace = useCallback(
    (id: string) => {
      onClose();
      setTraceId(id);
    },
    [setTraceId, onClose],
  );

  const handleThreadDelete = useCallback(() => {
    onClose();
    mutate({
      ids: [threadId],
      projectId,
    });
  }, [onClose, mutate, threadId, projectId]);

  const handleDeleteFeedbackScore = (name: string) => {
    threadFeedbackScoreDelete({
      names: [name],
      threadId,
      projectName,
      projectId,
    });
  };

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

  const bodyStyle = {
    ...(height && { height: `${height}px` }),
  };

  const renderThreadStatus = () => {
    if (!thread) {
      return null;
    }

    if (isInactiveThread) {
      return <ThreadStatusTag status={ThreadStatus.INACTIVE} />;
    }

    return (
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="outline"
            size="2xs"
            className="border-[#EBF2F5] bg-[#EBF2F5] hover:bg-[#EBF2F5]/80"
          >
            <MessageCircleMore className="mr-1 size-3" /> Active
            <ChevronDown className="ml-1 size-3.5" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-60">
          <DropdownMenuItem onClick={() => changeSetInactiveOpen(true)}>
            <MessageCircleOff className="mr-2 size-4" />
            Set as inactive
          </DropdownMenuItem>
          {/* <DropdownMenuSeparator />
          <Button variant="link" className="w-full" asChild>
            <Link
              to="/$workspaceName/configuration"
              params={{ workspaceName }}
              search={{
                tab: "workspace-preferences",
                editPreference: WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
              }}
              target="_blank"
              rel="noopener noreferrer"
            >
              Manage session timeout
            </Link>
          </Button> */}
        </DropdownMenuContent>
      </DropdownMenu>
    );
  };

  const renderHeader = () => {
    if (isThreadPending) {
      return <Loader />;
    }

    return (
      <div className="flex flex-col gap-1">
        <div className="flex items-center gap-2 overflow-x-hidden">
          <div className="relative flex size-[22px] shrink-0 items-center justify-center rounded-md bg-[#DEDEFD] text-[#1B1C7E]">
            <MessagesSquare className="size-3.5" />
          </div>
          <div className="comet-title-s truncate py-0.5">Thread</div>
          <div className="flex flex-auto"></div>

          {renderThreadStatus()}
        </div>
        <div className=" flex w-full items-center gap-3 overflow-x-hidden py-1">
          <TooltipWrapper content="Thread start time">
            <div className="flex flex-nowrap items-center gap-x-1.5 px-1 text-muted-slate">
              <Calendar className="size-4 shrink-0" />
              <span className="comet-body-s-accented truncate">
                {thread?.start_time ? formatDate(thread?.start_time) : "NA"}
              </span>
            </div>
          </TooltipWrapper>
          <TooltipWrapper content="Number of messages in the thread">
            <div className="flex flex-nowrap items-center gap-x-1.5 px-1 text-muted-slate">
              <Hash className="size-4 shrink-0" />
              <span className="comet-body-s-accented truncate">
                {thread?.number_of_messages
                  ? `${thread.number_of_messages} messages`
                  : "NA"}
              </span>
            </div>
          </TooltipWrapper>
          <TooltipWrapper content="Thread duration">
            <div className="flex flex-nowrap items-center gap-x-1.5 px-1 text-muted-slate">
              <Clock className="size-4 shrink-0" />
              <span className="comet-body-s-accented truncate">
                {formatDuration(thread?.duration, false)}
              </span>
            </div>
          </TooltipWrapper>
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
        <div className="mx-6" data-panel-tabs="true">
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value="messages">
              Messages
            </TabsTrigger>
            <TooltipWrapper content={disabledAnnotationExplainer}>
              <div>
                <TabsTrigger
                  variant="underline"
                  value="feedback_scores"
                  disabled={!isInactiveThread}
                >
                  Feedback scores
                </TabsTrigger>
              </div>
            </TooltipWrapper>
          </TabsList>
        </div>
        <TabsContent value="messages">
          <div style={bodyStyle}>
            <TraceMessages
              traces={traces}
              handleOpenTrace={handleOpenTrace}
              traceId={traceId}
            />
          </div>
        </TabsContent>
        <TabsContent value="feedback_scores" className="px-6">
          <FeedbackScoreTab
            onDeleteFeedbackScore={handleDeleteFeedbackScore}
            entityName="thread"
            feedbackScores={threadFeedbackScores}
            onAddHumanReview={() =>
              setActiveSection(DetailsActionSection.Annotations)
            }
            entityType="thread"
          />

          <ThreadFeedbackScoresInfo
            feedbackScores={threadFeedbackScores}
            onAddHumanReview={() =>
              setActiveSection(DetailsActionSection.Annotations)
            }
          />
        </TabsContent>
      </Tabs>
    );
  };

  const renderContent = () => {
    if (isThreadPending && isTracesPending) {
      return <Loader />;
    }

    if (!thread) {
      return <NoData />;
    }

    return (
      <div className="relative size-full">
        <ResizablePanelGroup direction="horizontal" autoSaveId="trace-sidebar">
          <ResizablePanel id="thread-viewer" defaultSize={70} minSize={50}>
            <div ref={ref} className="relative size-full">
              <div className="px-6 pb-6 pt-4" data-panel-header="true">
                {renderHeader()}
              </div>
              <div data-panel-body="true">{renderBody()}</div>
            </div>
          </ResizablePanel>
          {Boolean(currentActiveSection) && (
            <>
              <ResizableHandle />
              <ResizablePanel
                id="thread-last-section-viewer"
                defaultSize={30}
                minSize={30}
              >
                {currentActiveSection === DetailsActionSection.Annotations && (
                  <ThreadAnnotations
                    threadId={threadId}
                    projectId={projectId}
                    projectName={projectName}
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
                    feedbackScores={threadFeedbackScores}
                  />
                )}
                {currentActiveSection === DetailsActionSection.Comments && (
                  <ThreadComments
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
                    comments={threadComments}
                    threadId={thread.thread_model_id}
                    projectId={projectId}
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
        <div className="flex items-center">
          <Separator orientation="vertical" className="mx-3 h-4" />
          <Button
            variant="outline"
            size="sm"
            key="Go to project"
            onClick={() => {
              navigate({
                to: "/$workspaceName/projects/$projectId/traces",
                params: {
                  projectId,
                  workspaceName,
                },
                search: {
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
            View all traces
          </Button>
        </div>
        <div className="flex gap-2 pl-6">
          <DetailsActionSectionToggle
            activeSection={currentActiveSection}
            setActiveSection={setActiveSection}
            layoutSize={ButtonLayoutSize.Large}
            count={commentsCount}
            type={DetailsActionSection.Comments}
            disabled={!isInactiveThread}
            tooltipContent={disabledCommentsExplainer}
          />

          <DetailsActionSectionToggle
            activeSection={currentActiveSection}
            setActiveSection={setActiveSection}
            layoutSize={ButtonLayoutSize.Large}
            count={annotationCount}
            type={DetailsActionSection.Annotations}
            disabled={!isInactiveThread}
            tooltipContent={disabledAnnotationExplainer}
          />

          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" size="icon-sm">
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
              <DropdownMenuItem onClick={() => setPopupOpen(true)}>
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

          <SetInactiveConfirmDialog
            open={setInactiveOpen}
            setOpen={changeSetInactiveOpen}
            threadId={threadId}
            projectId={projectId}
          />
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
      initialWidth={0.5}
      horizontalNavigation={horizontalNavigation}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default ThreadDetailsPanel;
