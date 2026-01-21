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

interface TraceSelectionSectionProps {
  projectId: string;
  selectedTraceId: string | null | undefined;
  onSelectTrace: (traceId: string) => void;
}

const TraceSelectionSection: React.FC<TraceSelectionSectionProps> = ({
  projectId,
  selectedTraceId,
  onSelectTrace,
}) => {
  const [open, setOpen] = useState(false);
  const [searchText, setSearchText] = useState("");

  const { data: tracesData, isPending } = useTracesList(
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
      enabled: Boolean(projectId),
    },
  );

  const traces = tracesData?.content ?? [];
  const totalTraces = tracesData?.total ?? 0;
  const hasMoreTraces = totalTraces > 100;

  // Client-side filtering to match the displayed format
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

  const selectedTrace = useMemo(
    () => traces.find((t) => t.id === selectedTraceId),
    [traces, selectedTraceId],
  );

  const handleSelectTrace = (traceId: string) => {
    onSelectTrace(traceId);
    setOpen(false);
  };

  return (
    <div className="w-[300px]">
      <label className="comet-body-s-accented mb-2 block">Trace</label>
      <Popover open={open} onOpenChange={setOpen}>
        <PopoverTrigger asChild>
          <Button variant="outline" className="h-8 w-full justify-between">
            {selectedTrace ? (
              <span className="truncate">
                {selectedTrace.name
                  ? `${selectedTrace.name} (${selectedTrace.id.substring(
                      0,
                      8,
                    )})`
                  : selectedTrace.id}
              </span>
            ) : (
              <span className="text-muted-slate">Select a trace...</span>
            )}
            <ChevronDown className="ml-2 size-4 shrink-0" />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-[400px] p-0" align="start">
          <div className="flex flex-col">
            <div className="p-2">
              <SearchInput
                searchText={searchText}
                setSearchText={setSearchText}
                placeholder="Search traces..."
              />
            </div>
            <div className="max-h-[300px] overflow-y-auto">
              {isPending ? (
                <div className="p-4">
                  <Loader />
                </div>
              ) : filteredTraces.length > 0 ? (
                <>
                  {hasMoreTraces && !searchText && (
                    <div className="comet-body-xs px-4 py-2 text-muted-slate">
                      Showing the 100 most recent traces. Use search to find
                      specific traces.
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
              )}
            </div>
          </div>
        </PopoverContent>
      </Popover>
    </div>
  );
};

export default TraceSelectionSection;
