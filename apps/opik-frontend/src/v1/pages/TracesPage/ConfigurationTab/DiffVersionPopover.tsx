import React, { useState } from "react";
import { GitCompareArrows } from "lucide-react";

import { ConfigHistoryItem } from "@/types/agent-configs";
import { getTimeFromNow } from "@/lib/date";
import { Button } from "@/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import ConfigTagList from "./ConfigTagList";

type DiffVersionItemProps = {
  version: ConfigHistoryItem;
  onSelect: () => void;
};

const DiffVersionItem: React.FC<DiffVersionItemProps> = ({
  version,
  onSelect,
}) => {
  return (
    <button
      className="flex w-full cursor-pointer flex-col gap-1 rounded-md px-3 py-1.5 text-left hover:bg-muted"
      onClick={onSelect}
    >
      <div className="flex items-center gap-1.5">
        <span className="comet-body-s-accented shrink-0">{version.name}</span>
        <ConfigTagList
          tags={version.tags}
          size="sm"
          className="min-w-0 flex-1"
        />
      </div>
      <span className="comet-body-xs text-light-slate">
        {getTimeFromNow(version.created_at)}
      </span>
    </button>
  );
};

type DiffVersionPopoverProps = {
  currentItemId: string;
  versions: ConfigHistoryItem[];
  onSelectVersion: (item: ConfigHistoryItem) => void;
};

const DiffVersionPopover: React.FC<DiffVersionPopoverProps> = ({
  currentItemId,
  versions,
  onSelectVersion,
}) => {
  const [open, setOpen] = useState(false);

  const handleSelect = (item: ConfigHistoryItem) => {
    onSelectVersion(item);
    setOpen(false);
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button size="xs" variant="outline">
          <GitCompareArrows className="mr-1.5 size-3.5" />
          Diff
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-64 p-1">
        <div className="comet-body-xs-accented px-3 py-1.5 text-light-slate">
          Compare against
        </div>
        <div className="max-h-[40vh] overflow-y-auto">
          {versions.map((v) => {
            if (v.id === currentItemId) return null;
            return (
              <DiffVersionItem
                key={v.id}
                version={v}
                onSelect={() => handleSelect(v)}
              />
            );
          })}
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default DiffVersionPopover;
