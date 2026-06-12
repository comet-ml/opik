import React from "react";
import { ArrowUpDown, Check, ChevronDown } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";

export type LeaderboardSortKey =
  | "total_tokens"
  | "requests"
  | "skills"
  | "mcps"
  | "mcp_calls";

const SORT_OPTIONS: { key: LeaderboardSortKey; label: string }[] = [
  { key: "total_tokens", label: "By spend" },
  { key: "requests", label: "By requests" },
  { key: "skills", label: "By skills" },
  { key: "mcps", label: "By MCPs" },
  { key: "mcp_calls", label: "By MCP calls" },
];

interface LeaderboardSortSelectProps {
  value: LeaderboardSortKey;
  onChange: (value: LeaderboardSortKey) => void;
}

const LeaderboardSortSelect: React.FC<LeaderboardSortSelectProps> = ({
  value,
  onChange,
}) => {
  const current = SORT_OPTIONS.find((o) => o.key === value) ?? SORT_OPTIONS[0];

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" className="gap-2">
          <ArrowUpDown className="size-3.5 text-light-slate" />
          {current.label}
          <ChevronDown className="size-3.5 text-light-slate" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {SORT_OPTIONS.map((option) => (
          <DropdownMenuItem
            key={option.key}
            onClick={() => onChange(option.key)}
            className="gap-2"
          >
            <Check
              className={cn(
                "size-3.5",
                option.key === value ? "opacity-100" : "opacity-0",
              )}
            />
            {option.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default LeaderboardSortSelect;
