import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import find from "lodash/find";
import isBoolean from "lodash/isBoolean";
import isFunction from "lodash/isFunction";
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
import {
  DetailsActionSection,
  useDetailsActionSectionState,
} from "@/components/pages-shared/traces/DetailsActionSection";
import useTreeDetailsStore from "@/components/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";

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
  const [activeSection, setActiveSection] =
    useDetailsActionSectionState("lastSection");
  const { flattenedTree } = useTreeDetailsStore();

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
      size: 15000,
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

  const verticalNavigation = useMemo(() => {
    const id = spanId || traceId;
    const index = flattenedTree.findIndex((node) => node.id === id);
    const nextRowId = index !== -1 ? flattenedTree[index + 1]?.id : undefined;
    const previousRowId = index > 0 ? flattenedTree[index - 1]?.id : undefined;

    return {
      onChange: (shift: 1 | -1) => {
        const rowId = shift > 0 ? nextRowId : previousRowId;
        rowId && handleRowSelect(rowId);
      },
      hasNext: Boolean(nextRowId),
      hasPrevious: Boolean(previousRowId),
    };
  }, [spanId, traceId, handleRowSelect, flattenedTree]);

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
              activeSection={activeSection}
              setActiveSection={setActiveSection}
              isSpansLazyLoading={isSpansLazyLoading}
            />
          </ResizablePanel>
          {Boolean(activeSection) && (
            <>
              <ResizableHandle />
              <ResizablePanel
                id="last-section-viewer"
                defaultSize={40}
                minSize={30}
              >
                {activeSection === DetailsActionSection.Annotations && (
                  <TraceAnnotateViewer
                    data={dataToView}
                    spanId={spanId}
                    traceId={traceId}
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
                  />
                )}
                {activeSection === DetailsActionSection.Comments && (
                  <CommentsViewer
                    data={dataToView}
                    spanId={spanId}
                    traceId={traceId}
                    projectId={projectId}
                    activeSection={activeSection}
                    setActiveSection={setActiveSection}
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
          description="Deleting a trace will also remove the trace data from related experiment samples. This action can’t be undone. Are you sure you want to continue?"
          confirmText="Delete trace"
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
      panelId="traces"
      entity="trace"
      open={open}
      navigationContent={renderNavigationContent()}
      headerContent={renderHeaderContent()}
      onClose={onClose}
      horizontalNavigation={horizontalNavigation}
      verticalNavigation={verticalNavigation}
    >
      {renderContent()}
    </ResizableSidePanel>
  );
};

export default TraceDetailsPanel;
