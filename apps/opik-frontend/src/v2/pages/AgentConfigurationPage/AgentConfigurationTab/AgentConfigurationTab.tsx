import React, { useCallback, useMemo, useState } from "react";
import {
  Check,
  ChevronDown,
  Clock,
  FilePen,
  History,
  User,
} from "lucide-react";
import { StringParam, useQueryParam } from "use-query-params";

import { cn } from "@/lib/utils";
import { getTimeFromNow } from "@/lib/date";
import { generateBlueprintDescription } from "@/utils/agent-configurations";
import Loader from "@/shared/Loader/Loader";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import useConfigHistoryListInfinite from "@/api/agent-configs/useConfigHistoryListInfinite";
import { ConfigHistoryItem } from "@/types/agent-configs";
import AgentConfigurationHistoryTimeline from "./AgentConfigurationHistoryTimeline";
import AgentConfigurationDetailView from "./AgentConfigurationDetailView";
import AgentConfigurationEditView from "@/v2/pages-shared/agent-configuration/AgentConfigurationEditView";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import AgentConfigTagList from "./AgentConfigTagList";
import AgentConfigurationEmptyState from "@/v2/pages/AgentConfigurationPage/AgentConfigurationEmptyState";

type HistoryPopoverProps = {
  items: ConfigHistoryItem[];
  selectedIndex: number;
  onSelect: (index: number) => void;
};

const HistoryPopover: React.FC<HistoryPopoverProps> = ({
  items,
  selectedIndex,
  onSelect,
}) => {
  const [open, setOpen] = useState(false);
  const selectedItem = items[selectedIndex];

  const handleSelect = useCallback(
    (index: number) => {
      onSelect(index);
      setOpen(false);
    },
    [onSelect],
  );

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button className="flex h-8 w-full items-center justify-between rounded-md border bg-background px-3 dark:bg-accent-background">
          <div className="flex min-w-0 items-center gap-2">
            <TooltipWrapper content="Version history">
              <History className="size-3.5 shrink-0 text-light-slate" />
            </TooltipWrapper>
            {selectedItem && (
              <>
                <span className="comet-body-s-accented shrink-0">
                  {selectedItem.name}
                </span>
                <AgentConfigTagList
                  tags={selectedItem.tags}
                  size="sm"
                  maxWidth={150}
                />
              </>
            )}
          </div>
          <ChevronDown className="size-4 shrink-0" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        align="start"
        sideOffset={4}
        className="max-h-[50vh] w-[var(--radix-popover-trigger-width)] overflow-y-auto p-1"
      >
        {items.map((item, index) => {
          const isSelected = index === selectedIndex;
          const blueprintDescription =
            item.description || generateBlueprintDescription(item.values);

          return (
            <button
              key={item.id}
              className={cn(
                "flex w-full flex-col rounded-md px-3 py-2 text-left",
                isSelected
                  ? "bg-primary-foreground"
                  : "hover:bg-primary-foreground/60",
              )}
              onClick={() => handleSelect(index)}
            >
              <div className="flex items-center gap-1.5">
                <Check
                  className={cn("size-3 shrink-0", !isSelected && "invisible")}
                />
                <span className="comet-body-s-accented">{item.name}</span>
                <AgentConfigTagList tags={item.tags} size="sm" maxWidth={150} />
              </div>
              <div className="mt-1 flex flex-col gap-1 pl-[18px]">
                <p className="comet-body-xs flex min-w-0 items-center gap-1 text-light-slate">
                  <FilePen className="size-3 shrink-0" />
                  <TooltipWrapper content={blueprintDescription}>
                    <span className="w-fit max-w-full truncate">
                      {blueprintDescription}
                    </span>
                  </TooltipWrapper>
                </p>
                <div className="comet-body-xs flex items-center gap-1 text-light-slate">
                  <Clock className="size-3 shrink-0" />
                  <span>{getTimeFromNow(item.created_at)}</span>
                  {item.created_by && (
                    <span className="ml-1 flex items-center gap-1">
                      <User className="size-3 shrink-0" />
                      {item.created_by}
                    </span>
                  )}
                </div>
              </div>
            </button>
          );
        })}
      </PopoverContent>
    </Popover>
  );
};

type AgentConfigurationTabProps = {
  projectId: string;
};

const AgentConfigurationTab: React.FC<AgentConfigurationTabProps> = ({
  projectId,
}) => {
  const [selectedId, setSelectedId] = useQueryParam("configId", StringParam, {
    updateType: "replaceIn",
  });
  const [editItem, setEditItem] = useState<ConfigHistoryItem | null>(null);

  const { data, isPending, hasNextPage, fetchNextPage, isFetchingNextPage } =
    useConfigHistoryListInfinite({ projectId });

  const allRows = useMemo(
    () => data?.pages.flatMap((p) => p.content) ?? [],
    [data],
  );

  const selectedIndex = useMemo(() => {
    if (!selectedId) return 0;
    const idx = allRows.findIndex((r) => r.id === selectedId);
    return idx >= 0 ? idx : 0;
  }, [allRows, selectedId]);

  const handleSelect = useCallback(
    (index: number) => setSelectedId(allRows[index]?.id ?? undefined),
    [allRows, setSelectedId],
  );

  if (isPending) {
    return <Loader />;
  }

  if (allRows.length === 0) {
    return (
      <div className="flex min-h-full flex-col px-6 pt-4">
        <div className="mb-1 flex min-h-7 items-center">
          <h1 className="comet-body-accented truncate break-words">
            Agent configuration
          </h1>
        </div>
        <AgentConfigurationEmptyState />
      </div>
    );
  }

  const selectedItem = allRows[selectedIndex] as ConfigHistoryItem;

  if (editItem) {
    return (
      <div className="w-full p-4">
        <AgentConfigurationEditView
          item={editItem}
          projectId={projectId}
          onSaved={() => {
            setSelectedId(undefined);
            setEditItem(null);
          }}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col lg:flex-row lg:gap-0">
      <div className="mx-6 mt-5 flex flex-col gap-4 lg:hidden">
        <h1 className="comet-body-accented">Agent configuration</h1>
        <HistoryPopover
          items={allRows}
          selectedIndex={selectedIndex}
          onSelect={handleSelect}
        />
      </div>

      <div className="min-w-0 flex-1 [overflow-anchor:none] lg:w-[50vw]">
        <div className="mx-6 mt-5 hidden lg:block">
          <h1 className="comet-body-accented">Agent configuration</h1>
        </div>

        {selectedItem ? (
          <AgentConfigurationDetailView
            item={selectedItem}
            projectId={projectId}
            versions={allRows}
            onEdit={() => setEditItem(allRows[0])}
          />
        ) : (
          <div className="flex h-full items-center justify-center text-muted-slate">
            Select a version to view its configuration
          </div>
        )}
      </div>

      <div className="hidden w-[25vw] shrink-0 pr-2 lg:block">
        <p className="comet-body-s-accented ml-3 mt-6">Version history</p>

        <AgentConfigurationHistoryTimeline
          items={allRows}
          selectedIndex={selectedIndex}
          onSelect={handleSelect}
          hasNextPage={hasNextPage}
          isFetchingNextPage={isFetchingNextPage}
          onLoadMore={fetchNextPage}
        />
      </div>
    </div>
  );
};

export default AgentConfigurationTab;
