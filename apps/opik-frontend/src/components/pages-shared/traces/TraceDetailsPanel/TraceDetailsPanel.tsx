import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import find from "lodash/find";
import { createEnumParam, useQueryParam } from "use-query-params";

import { Trash } from "lucide-react";

import { OnChangeFn } from "@/types/shared";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import { Separator } from "@/components/ui/separator";
import useTraceById from "@/api/traces/useTraceById";
import Loader from "@/components/shared/Loader/Loader";
import TraceDataViewer from "./TraceDataViewer/TraceDataViewer";
import TraceTreeViewer from "./TraceTreeViewer/TraceTreeViewer";
import TraceAnnotateViewer from "./TraceAnnotateViewer/TraceAnnotateViewer";
import NoData from "@/components/shared/NoData/NoData";
import { Span } from "@/types/traces";
import ResizableSidePanel from "@/components/shared/ResizableSidePanel/ResizableSidePanel";
import useTraceDeleteMutation from "@/api/traces/useTraceDeleteMutation";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import CommentsViewer from "./CommentsViewer/CommentsViewer";
import useLazySpansList from "@/api/traces/useLazySpansList";

type TraceDetailsPanelProps = {
  projectId?: string;
  traceId: string;
  spanId: string;
  setSpanId: OnChangeFn<string | null | undefined>;
  setThreadId?: OnChangeFn<string | null | undefined>;
  hasPreviousRow?: boolean;
  hasNextRow?: boolean;
  open: boolean;
  onClose: () => void;
  onRowChange?: (shift: number) => void;
};

export const LastSection = {
  Annotations: "annotations",
  Comments: "comments",
} as const;
export type LastSectionValue = (typeof LastSection)[keyof typeof LastSection];

export const LastSectionParam = createEnumParam<LastSectionValue>([
  "annotations",
  "comments",
]);

const TraceDetailsPanel: React.FunctionComponent<TraceDetailsPanelProps> = ({
  projectId: externalProjectId,
  traceId,
  spanId,
  setSpanId,
  setThreadId,
  hasPreviousRow,
  hasNextRow,
  onClose,
  open,
  onRowChange,
}) => {
  const [popupOpen, setPopupOpen] = useState<boolean>(false);
  const [lastSection, setLastSection] = useQueryParam(
    "lastSection",
    LastSectionParam,
    {
      updateType: "replaceIn",
    },
  );

  const { data: trace, isPending: isTracePending } = useTraceById(
    {
      traceId,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId),
    },
  );

  const projectId = externalProjectId || trace?.project_id || "";

  const {
    query: { data: spansData, isPending: isSpansPending },
    isLazyLoading: isSpansLazyLoading,
  } = useLazySpansList(
    {
      traceId,
      projectId,
      page: 1,
      size: 1000,
    },
    {
      placeholderData: keepPreviousData,
      enabled: Boolean(traceId) && Boolean(projectId),
    },
  );

  const traceDeleteMutation = useTraceDeleteMutation();

  const handleTraceDelete = useCallback(() => {
    onClose();
    traceDeleteMutation.mutate({
      traceId,
      projectId,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [traceId, projectId, onClose]);

  const handleRowSelect = useCallback(
    (id: string) => setSpanId(id === traceId ? "" : id),
    [setSpanId, traceId],
  );

  const dataToView = useMemo(() => {
    return spanId
      ? find(spansData?.content || [], (span: Span) => span.id === spanId) ??
          trace
      : trace;
  }, [spanId, spansData?.content, trace]);

  const renderContent = () => {
    if (isTracePending || isSpansPending) {
      return <Loader />;
    }

    if (!dataToView || !trace) {
      return <NoData />;
    }

    return (
      <div className="relative size-full">
        <ResizablePanelGroup direction="horizontal" autoSaveId="trace-sidebar">
          <ResizablePanel id="tree-viewer" defaultSize={40} minSize={20}>
            <TraceTreeViewer
              trace={trace}
              spans={spansData?.content}
              rowId={spanId || traceId}
              onSelectRow={handleRowSelect}
              isSpansLazyLoading={isSpansLazyLoading}
            />
          </ResizablePanel>
          <ResizableHandle />
          <ResizablePanel id="data-viever" defaultSize={60} minSize={30}>
            <TraceDataViewer
              data={dataToView}
              trace={trace}
              projectId={projectId}
              spanId={spanId}
              traceId={traceId}
              lastSection={lastSection}
              setLastSection={setLastSection}
              isSpansLazyLoading={isSpansLazyLoading}
            />
          </ResizablePanel>
          {Boolean(lastSection) && (
            <>
              <ResizableHandle />
              <ResizablePanel
                id="last-section-viewer"
                defaultSize={40}
                minSize={30}
              >
                {lastSection === LastSection.Annotations && (
                  <TraceAnnotateViewer
                    data={dataToView}
                    spanId={spanId}
                    traceId={traceId}
                    lastSection={lastSection}
                    setLastSection={setLastSection}
                  />
                )}
                {lastSection === LastSection.Comments && (
                  <CommentsViewer
                    data={dataToView}
                    spanId={spanId}
                    traceId={traceId}
                    projectId={projectId}
                    lastSection={lastSection}
                    setLastSection={setLastSection}
                  />
                )}
              </ResizablePanel>
            </>
          )}
        </ResizablePanelGroup>
      </div>
    );
  };

  const renderNavigationContent = () => {
    if (setThreadId && trace?.thread_id) {
      return (
        <>
          <Separator orientation="vertical" className="mx-2 h-8" />
          <Button
            variant="outline"
            size="sm"
            onClick={() => setThreadId(trace.thread_id)}
          >
            Go to thread
          </Button>
        </>
      );
    }

    return null;
  };

  const renderHeaderContent = () => {
    return (
      <div className="flex gap-2">
        <ConfirmDialog
          open={popupOpen}
          setOpen={setPopupOpen}
          onConfirm={handleTraceDelete}
          title="Delete trace"
          description="Are you sure you want to delete this trace?"
          confirmText="Delete trace"
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
      panelId="traces"
      entity="trace"
      open={open}
      navigationContent={renderNavigationContent()}
      headerContent={renderHeaderContent()}
      hasPreviousRow={hasPreviousRow}
      hasNextRow={hasNextRow}
      onClose={onClose}
      onRowChange={onRowChange}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default TraceDetailsPanel;
