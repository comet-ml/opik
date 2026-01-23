import React, { useState, useMemo } from "react";
import { ChevronDown } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import Loader from "@/components/shared/Loader/Loader";
import useTracesList from "@/api/traces/useTracesList";
import useThreadsList from "@/api/traces/useThreadsList";
import { ContextType } from "@/types/custom-view";

interface ContextSelectionSectionProps {
  projectId: string;
  contextType: ContextType;
  selectedTraceId: string | null | undefined;
  selectedThreadId: string | null | undefined;
  onContextTypeChange: (type: ContextType) => void;
  onSelectTrace: (traceId: string) => void;
  onSelectThread: (threadId: string) => void;
}

const ContextSelectionSection: React.FC<ContextSelectionSectionProps> = ({
  projectId,
  contextType,
  selectedTraceId,
  selectedThreadId,
  onContextTypeChange,
  onSelectTrace,
  onSelectThread,
}) => {
  const [open, setOpen] = useState(false);
  const [searchText, setSearchText] = useState("");

  // Fetch traces
  const { data: tracesData, isPending: isTracesPending } = useTracesList(
    {
      projectId,
      page: 1,
      size: 100,
      sorting: [{ id: "start_time", desc: true }],
      filters: [],
      search: searchText,
      truncate: true,
    },
    {
      placeholderData: keepPreviousData,
      enabled: contextType === "trace" && Boolean(projectId),
    },
  );

  // Fetch threads
  const { data: threadsData, isPending: isThreadsPending } = useThreadsList(
    {
      projectId,
      page: 1,
      size: 100,
      sorting: [{ id: "start_time", desc: true }],
      filters: [],
      search: searchText,
      truncate: true,
    },
    {
      placeholderData: keepPreviousData,
      enabled: contextType === "thread" && Boolean(projectId),
    },
  );

  const traces = tracesData?.content ?? [];
  const totalTraces = tracesData?.total ?? 0;
  const hasMoreTraces = totalTraces > 100;

  const threads = threadsData?.content ?? [];
  const totalThreads = threadsData?.total ?? 0;
  const hasMoreThreads = totalThreads > 100;

  const isPending = contextType === "trace" ? isTracesPending : isThreadsPending;

  // Client-side filtering for traces
  const filteredTraces = useMemo(() => {
    if (!searchText) return traces;

    const lowerSearch = searchText.toLowerCase();
    return traces.filter((trace) => {
      const name = trace.name?.toLowerCase() || "";
      const fullId = trace.id.toLowerCase();
      const truncatedId = trace.id.substring(0, 8).toLowerCase();

      return (
        name.includes(lowerSearch) ||
        fullId.includes(lowerSearch) ||
        truncatedId.includes(lowerSearch)
      );
    });
  }, [traces, searchText]);

  // Client-side filtering for threads
  const filteredThreads = useMemo(() => {
    if (!searchText) return threads;

    const lowerSearch = searchText.toLowerCase();
    return threads.filter((thread) => {
      const fullId = thread.id.toLowerCase();
      const truncatedId = thread.id.substring(0, 8).toLowerCase();

      return fullId.includes(lowerSearch) || truncatedId.includes(lowerSearch);
    });
  }, [threads, searchText]);

  const selectedTrace = useMemo(
    () => traces.find((t) => t.id === selectedTraceId),
    [traces, selectedTraceId],
  );

  const selectedThread = useMemo(
    () => threads.find((t) => t.id === selectedThreadId),
    [threads, selectedThreadId],
  );

  const handleSelectTrace = (traceId: string) => {
    onSelectTrace(traceId);
    setOpen(false);
  };

  const handleSelectThread = (threadId: string) => {
    onSelectThread(threadId);
    setOpen(false);
  };

  const getButtonText = () => {
    if (contextType === "trace") {
      if (selectedTrace) {
        return selectedTrace.name
          ? `${selectedTrace.name} (${selectedTrace.id.substring(0, 8)})`
          : selectedTrace.id;
      }
      return "Select a trace...";
    } else {
      if (selectedThread) {
        return `Thread ${selectedThread.id.substring(0, 8)}`;
      }
      return "Select a thread...";
    }
  };

  return (
    <div className="flex items-end gap-2">
      {/* Segmented Control for Context Type */}
      <div>
        <label className="comet-body-s-accented mb-2 block">Context</label>
        <div className="inline-flex h-8 items-center rounded-md border bg-muted p-1">
          <button
            onClick={() => onContextTypeChange("trace")}
            className={`rounded px-3 py-1 text-sm font-medium transition-colors ${
              contextType === "trace"
                ? "bg-background text-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            Trace
          </button>
          <button
            onClick={() => onContextTypeChange("thread")}
            className={`rounded px-3 py-1 text-sm font-medium transition-colors ${
              contextType === "thread"
                ? "bg-background text-foreground shadow-sm"
                : "text-muted-foreground hover:text-foreground"
            }`}
          >
            Thread
          </button>
        </div>
      </div>

      {/* Dropdown for Selection */}
      <div className="w-[300px]">
        <label className="comet-body-s-accented mb-2 block">
          {contextType === "trace" ? "Select Trace" : "Select Thread"}
        </label>
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <Button variant="outline" className="h-8 w-full justify-between">
              <span className="truncate">{getButtonText()}</span>
              <ChevronDown className="ml-2 size-4 shrink-0" />
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-[400px] p-0" align="start">
            <div className="flex flex-col">
              <div className="p-2">
                <SearchInput
                  searchText={searchText}
                  setSearchText={setSearchText}
                  placeholder={`Search ${contextType === "trace" ? "traces" : "threads"}...`}
                />
              </div>
              <div className="max-h-[300px] overflow-y-auto">
                {isPending ? (
                  <div className="p-4">
                    <Loader />
                  </div>
                ) : contextType === "trace" ? (
                  filteredTraces.length > 0 ? (
                    <>
                      {hasMoreTraces && !searchText && (
                        <div className="comet-body-xs px-4 py-2 text-muted-slate">
                          Showing the 100 most recent traces. Use search to
                          find specific traces.
                        </div>
                      )}
                      {filteredTraces.map((trace) => (
                        <Button
                          key={trace.id}
                          variant="ghost"
                          className="w-full justify-start truncate px-3 py-2"
                          onClick={() => handleSelectTrace(trace.id)}
                        >
                          {trace.name
                            ? `${trace.name} (${trace.id.substring(0, 8)})`
                            : trace.id}
                        </Button>
                      ))}
                    </>
                  ) : (
                    <div className="p-4 text-center text-muted-slate">
                      No traces found
                    </div>
                  )
                ) : filteredThreads.length > 0 ? (
                  <>
                    {hasMoreThreads && !searchText && (
                      <div className="comet-body-xs px-4 py-2 text-muted-slate">
                        Showing the 100 most recent threads. Use search to find
                        specific threads.
                      </div>
                    )}
                    {filteredThreads.map((thread) => (
                      <Button
                        key={thread.id}
                        variant="ghost"
                        className="w-full justify-start truncate px-3 py-2"
                        onClick={() => handleSelectThread(thread.id)}
                      >
                        Thread {thread.id.substring(0, 8)}
                      </Button>
                    ))}
                  </>
                ) : (
                  <div className="p-4 text-center text-muted-slate">
                    No threads found
                  </div>
                )}
              </div>
            </div>
          </PopoverContent>
        </Popover>
      </div>
    </div>
  );
};

export default ContextSelectionSection;
