import React from "react";
import { Clock, GitCompareArrows } from "lucide-react";

import { getTimeFromNow } from "@/lib/date";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import VersionTagList from "./VersionTagList";
import type { VersionHistoryItem } from "./VersionHistoryTimeline";

interface DiffVersionMenuProps {
  currentItemId: string;
  versions: VersionHistoryItem[];
  onSelectVersion: (item: VersionHistoryItem) => void;
  triggerLabel?: string;
}

const DiffVersionMenu: React.FC<DiffVersionMenuProps> = ({
  currentItemId,
  versions,
  onSelectVersion,
  triggerLabel = "Show diff",
}) => {
  const selectableVersions = versions.filter((v) => v.id !== currentItemId);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          size="sm"
          variant="ghost"
          disabled={selectableVersions.length === 0}
        >
          <GitCompareArrows className="mr-1.5 size-3.5" />
          {triggerLabel}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel size="sm">Compare against</DropdownMenuLabel>
        <DropdownMenuSeparator className="bg-border" />
        <div className="max-h-[40vh] overflow-y-auto">
          {selectableVersions.map((version) => (
            <DropdownMenuItem
              key={version.id}
              size="sm"
              className="gap-2"
              onSelect={() => onSelectVersion(version)}
            >
              <div className="flex min-w-0 flex-1 items-center gap-1.5">
                <span className="shrink-0 text-foreground">
                  {version.label}
                </span>
                <VersionTagList tags={version.tags} size="sm" maxWidth={120} />
              </div>
              <span className="comet-body-xs flex shrink-0 items-center gap-1 text-muted-slate">
                <Clock className="size-3 shrink-0" />
                {getTimeFromNow(version.created_at)}
              </span>
            </DropdownMenuItem>
          ))}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DiffVersionMenu;
