import React from "react";
import { Clock, GitCompareArrows } from "lucide-react";

import { ConfigHistoryItem } from "@/types/agent-configs";
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
import AgentConfigTagList from "./AgentConfigTagList";

type DiffVersionMenuProps = {
  currentItemId: string;
  versions: ConfigHistoryItem[];
  onSelectVersion: (item: ConfigHistoryItem) => void;
};

const DiffVersionMenu: React.FC<DiffVersionMenuProps> = ({
  currentItemId,
  versions,
  onSelectVersion,
}) => {
  const selectableVersions = versions.filter((v) => v.id !== currentItemId);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="xs" variant="outline">
          <GitCompareArrows className="mr-1.5 size-3.5" />
          Diff
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64">
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
                <span className="shrink-0 text-foreground">{version.name}</span>
                <AgentConfigTagList
                  tags={version.tags}
                  size="sm"
                  maxWidth={120}
                />
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
