import React, { useMemo } from "react";
import { ChevronDown, SlidersHorizontal } from "lucide-react";
import { Link } from "@tanstack/react-router";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import useAppStore from "@/store/AppStore";
import { Environment } from "@/types/environments";
import { EnvironmentSquare } from "@/shared/EnvironmentLabel/EnvironmentLabel";
import { CONFIGURATION_TABS } from "@/v2/constants/configuration";
import { ENVIRONMENT_UNTAGGED_VALUE } from "@/lib/filters";

export const ALL_ENVIRONMENTS_VALUE = "";

const ALL_ENVIRONMENTS_LABEL = "All environments";
const UNTAGGED_LABEL = "Untagged";

type EnvironmentFilterSelectProps = {
  value: string;
  onChange: (value: string) => void;
};

const EnvironmentFilterSelect: React.FC<EnvironmentFilterSelectProps> = ({
  value,
  onChange,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data } = useEnvironmentsList();

  const environments = useMemo<Environment[]>(
    () => (data?.content ?? []).slice().sort((a, b) => a.position - b.position),
    [data?.content],
  );

  const selectedEnvironment = environments.find((env) => env.name === value);

  const triggerLabel = (() => {
    if (selectedEnvironment) return selectedEnvironment.name;
    if (value === ENVIRONMENT_UNTAGGED_VALUE) return UNTAGGED_LABEL;
    return ALL_ENVIRONMENTS_LABEL;
  })();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          className="min-w-40 justify-between gap-2 font-normal focus-visible:border-primary focus-visible:ring-0"
        >
          <span className="flex min-w-0 items-center gap-1.5 truncate">
            {selectedEnvironment && (
              <EnvironmentSquare color={selectedEnvironment.color} />
            )}
            {triggerLabel}
          </span>
          <ChevronDown className="size-4 shrink-0 text-foreground opacity-50" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        className="min-w-[var(--radix-dropdown-menu-trigger-width)]"
      >
        <DropdownMenuItem
          selected={value === ALL_ENVIRONMENTS_VALUE}
          onSelect={() => onChange(ALL_ENVIRONMENTS_VALUE)}
        >
          {ALL_ENVIRONMENTS_LABEL}
        </DropdownMenuItem>
        <DropdownMenuItem
          selected={value === ENVIRONMENT_UNTAGGED_VALUE}
          onSelect={() => onChange(ENVIRONMENT_UNTAGGED_VALUE)}
        >
          {UNTAGGED_LABEL}
        </DropdownMenuItem>
        {environments.length > 0 && <DropdownMenuSeparator />}
        <div className="max-h-80 overflow-y-auto">
          {environments.map((env) => (
            <DropdownMenuItem
              key={env.id}
              selected={value === env.name}
              onSelect={() => onChange(env.name)}
            >
              <div className="flex min-w-0 items-center gap-2">
                <EnvironmentSquare color={env.color} />
                <span className="truncate">{env.name}</span>
              </div>
            </DropdownMenuItem>
          ))}
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuItem asChild>
          <Link
            to="/$workspaceName/configuration"
            params={{ workspaceName }}
            search={{ tab: CONFIGURATION_TABS.ENVIRONMENTS }}
          >
            <SlidersHorizontal className="mr-2 size-3.5 shrink-0 text-muted-slate" />
            Manage environments
          </Link>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default EnvironmentFilterSelect;
