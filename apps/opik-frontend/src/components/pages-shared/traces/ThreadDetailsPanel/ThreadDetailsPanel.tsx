import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { Calendar, Clock, Tag, Trash } from "lucide-react";

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

type ThreadDetailsPanelProps = {
  projectId: string;
  threadId: string;
  traceId?: string;
  setTraceId: OnChangeFn<string | null | undefined>;
  hasPreviousRow?: boolean;
  hasNextRow?: boolean;
  open: boolean;
  onClose: () => void;
  onRowChange?: (shift: number) => void;
};

const ThreadDetailsPanel: React.FC<ThreadDetailsPanelProps> = ({
  projectId,
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
  const [height, setHeight] = useState<number>(0);
  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    const contentHeight = node.clientHeight;
    const headerHeight =
      node.querySelector('[data-panel-header="true"]')?.clientHeight || 0;
    const BOTTOM_PADDING = 16;
    setHeight(contentHeight - headerHeight - BOTTOM_PADDING);
  });

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

  const bodyStyle = {
    ...(height && { height: `${height}px` }),
  };

  const renderHeader = () => {
    if (isThreadPending) {
      return <Loader />;
    }

    return (
      <div className="flex flex-wrap gap-2">
        <h3 className="comet-title-m mr-2 text-foreground-secondary">Thread</h3>
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
            <Tag className="size-4 shrink-0" />
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
        <div className="flex flex-auto"></div>
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
    );
  };

  const renderBody = () => {
    if (isTracesPending) {
      return <Loader />;
    }

    return (
      <TraceMessages
        traces={traces}
        handleOpenTrace={handleOpenTrace}
        traceId={traceId}
      />
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
      <div ref={ref} className="relative size-full px-6">
        <div className="border-b py-4" data-panel-header="true">
          {renderHeader()}
        </div>
        <div style={bodyStyle} data-panel-body="true">
          {renderBody()}
        </div>
      </div>
    );
  };

  const renderHeaderContent = () => {
    return (
      <div className="flex gap-2">
        <ConfirmDialog
          open={popupOpen}
          setOpen={setPopupOpen}
          onConfirm={handleThreadDelete}
          title="Delete thread"
          description="Deleting a thread will also remove all traces linked to it and their data. This action can’t be undone. Are you sure you want to continue?"
          confirmText="Delete thread"
          confirmButtonVariant="destructive"
        />
        <Button variant="outline" size="sm" onClick={() => setPopupOpen(true)}>
          <Trash className="mr-2 size-4" />
          Delete
        </Button>
      </div>
    );
  };

  return (
    <ResizableSidePanel
      panelId="thread"
      entity="thread"
      open={open}
      headerContent={renderHeaderContent()}
      hasPreviousRow={hasPreviousRow}
      hasNextRow={hasNextRow}
      onClose={onClose}
      onRowChange={onRowChange}
      initialWidth={0.5}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default ThreadDetailsPanel;
