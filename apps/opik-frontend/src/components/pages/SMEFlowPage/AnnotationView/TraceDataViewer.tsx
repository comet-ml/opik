import React, { useCallback, useState, useEffect } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import { Loader2, LayoutGrid, List } from "lucide-react";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { Button } from "@/components/ui/button";
import SyntaxHighlighter from "@/components/shared/SyntaxHighlighter/SyntaxHighlighter";
import { Trace } from "@/types/traces";
import { ViewTree, loadView } from "@/lib/data-view";
import { useSMEFlow } from "../SMEFlowContext";
import { useAnnotationTreeState } from "./AnnotationTreeStateContext";
import useTraceById from "@/api/traces/useTraceById";
import { useUnifiedMedia } from "@/hooks/useUnifiedMedia";
import { MediaProvider } from "@/components/shared/PrettyLLMMessage/llmMessages";
import AttachmentsList from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDataViewer/AttachmentsList";
import { viewModeStorage } from "@/lib/viewModeStorage";
import AnnotationCustomViewPanel from "./AnnotationCustomViewPanel";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const STALE_TIME = 5 * 60 * 1000; // 5 minutes

// Storage key prefix for custom views (matches CustomViewDemoPage)
const STORAGE_KEY_PREFIX = "custom-view:";

const TraceDataViewer: React.FC = () => {
  const { currentItem, nextItem } = useSMEFlow();
  const { state, updateScrollTop } = useAnnotationTreeState();

  const trace = currentItem as Trace;
  const nextTrace = nextItem as Trace | undefined;

  // View mode state
  const [viewMode, setViewMode] = useState<"classic" | "custom">(() =>
    viewModeStorage.getViewMode(),
  );
  const [savedViewTree, setSavedViewTree] = useState<ViewTree | null>(null);

  // Fetch full trace data (not truncated)
  const { data: fullTrace, isFetching } = useTraceById(
    {
      traceId: trace?.id || "",
    },
    {
      enabled: !!trace?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  // Preload next trace data
  useTraceById(
    {
      traceId: nextTrace?.id || "",
    },
    {
      enabled: !!nextTrace?.id,
      placeholderData: keepPreviousData,
      staleTime: STALE_TIME,
    },
  );

  const displayTrace = fullTrace || trace;

  // Use unified media hook to fetch all media and get transformed data
  const { media, transformedInput, transformedOutput } =
    useUnifiedMedia(displayTrace);

  // Load saved view tree for the current trace's project
  useEffect(() => {
    if (!displayTrace?.project_id) return;

    const viewTree = loadView(
      `${STORAGE_KEY_PREFIX}${displayTrace.project_id}`,
    );
    setSavedViewTree(viewTree);
  }, [displayTrace?.project_id]);

  // Toggle handler
  const handleToggleViewMode = useCallback(() => {
    const newMode = viewMode === "classic" ? "custom" : "classic";
    setViewMode(newMode);
    viewModeStorage.setViewMode(newMode);
  }, [viewMode]);

  // Handlers for scroll position changes
  const handleInputScrollChange = useCallback(
    (updaterOrValue: number | ((old: number) => number)) => {
      const newScrollTop =
        typeof updaterOrValue === "function"
          ? updaterOrValue(state.input.scrollTop)
          : updaterOrValue;
      updateScrollTop("input", newScrollTop);
    },
    [updateScrollTop, state.input.scrollTop],
  );

  const handleOutputScrollChange = useCallback(
    (updaterOrValue: number | ((old: number) => number)) => {
      const newScrollTop =
        typeof updaterOrValue === "function"
          ? updaterOrValue(state.output.scrollTop)
          : updaterOrValue;
      updateScrollTop("output", newScrollTop);
    },
    [updateScrollTop, state.output.scrollTop],
  );

  return (
    <div className="relative pr-4">
      {/* Loading indicator */}
      {isFetching && (
        <div className="absolute right-6 top-2 z-10">
          <Loader2 className="size-4 animate-spin text-slate-400" />
        </div>
      )}

      {/* View mode toggle header */}
      <div className="mb-4 flex items-center justify-between border-b pb-3">
        <div className="comet-body-s-accented">
          {viewMode === "classic" ? "Standard View" : "Custom View"}
        </div>
        <div className="flex items-center gap-2">
          {viewMode === "custom" && !savedViewTree && (
            <span className="comet-body-xs text-muted-slate">
              No saved view for this project
            </span>
          )}
          <TooltipWrapper
            content={`Switch to ${
              viewMode === "classic" ? "custom" : "standard"
            } view`}
          >
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={handleToggleViewMode}
            >
              {viewMode === "classic" ? (
                <LayoutGrid className="size-4" />
              ) : (
                <List className="size-4" />
              )}
            </Button>
          </TooltipWrapper>
        </div>
      </div>

      {/* Conditional rendering based on view mode */}
      {viewMode === "classic" ? (
        <MediaProvider media={media}>
          <Accordion
            type="multiple"
            className="w-full"
            defaultValue={["attachments", "input", "output"]}
          >
            {displayTrace && <AttachmentsList media={media} />}
            <AccordionItem className="group" value="input">
              <AccordionTrigger>Input</AccordionTrigger>
              <AccordionContent
                forceMount
                className="group-data-[state=closed]:hidden"
              >
                <SyntaxHighlighter
                  data={transformedInput}
                  prettifyConfig={{ fieldType: "input" }}
                  preserveKey="syntax-highlighter-annotation-input"
                  withSearch
                  scrollPosition={state.input.scrollTop}
                  onScrollPositionChange={handleInputScrollChange}
                  maxHeight="400px"
                />
              </AccordionContent>
            </AccordionItem>

            <AccordionItem className="group" value="output">
              <AccordionTrigger>Output</AccordionTrigger>
              <AccordionContent
                forceMount
                className="group-data-[state=closed]:hidden"
              >
                <SyntaxHighlighter
                  data={transformedOutput}
                  prettifyConfig={{ fieldType: "output" }}
                  preserveKey="syntax-highlighter-annotation-output"
                  withSearch
                  scrollPosition={state.output.scrollTop}
                  onScrollPositionChange={handleOutputScrollChange}
                  maxHeight="400px"
                />
              </AccordionContent>
            </AccordionItem>

            <AccordionItem className="group" value="metadata">
              <AccordionTrigger>Metadata</AccordionTrigger>
              <AccordionContent
                forceMount
                className="group-data-[state=closed]:hidden"
              >
                <SyntaxHighlighter
                  data={displayTrace?.metadata || {}}
                  preserveKey="syntax-highlighter-annotation-metadata"
                  withSearch
                  maxHeight="400px"
                />
              </AccordionContent>
            </AccordionItem>
          </Accordion>
        </MediaProvider>
      ) : savedViewTree && displayTrace ? (
        <AnnotationCustomViewPanel
          trace={displayTrace}
          viewTree={savedViewTree}
        />
      ) : (
        <div className="flex flex-col items-center justify-center py-12 text-center">
          <LayoutGrid className="mb-4 size-12 text-muted-slate" />
          <div className="comet-body-s mb-2 text-muted-slate">
            No custom view saved for this project
          </div>
          <div className="comet-body-xs text-muted-slate">
            Create a custom view in the Custom View Demo page first
          </div>
        </div>
      )}
    </div>
  );
};

export default TraceDataViewer;
