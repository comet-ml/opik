import React from "react";
import { Settings2 } from "lucide-react";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Switch } from "@/ui/switch";
import { AGENT_INSIGHTS_JOB_STATUS } from "@/types/signals";
import useUpdateAgentInsightsJobMutation from "@/api/signals/useUpdateAgentInsightsJobMutation";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import usePluginsStore from "@/store/PluginsStore";

type DiagnosticsSettingsMenuProps = {
  projectId: string;
  enabled: boolean;
  // Undefined hides "Edit project guidance" (guidance toggle off).
  onEditGuidance?: () => void;
};

const DiagnosticsSettingsMenu: React.FC<DiagnosticsSettingsMenuProps> = ({
  projectId,
  enabled,
  onEditGuidance,
}) => {
  const updateMutation = useUpdateAgentInsightsJobMutation();
  const BillingLink = usePluginsStore((state) => state.BillingLink);

  const handleToggle = (on: boolean) => {
    trackEvent(
      on
        ? OpikEvent.DIAGNOSTICS_AUTO_ENABLED
        : OpikEvent.DIAGNOSTICS_AUTO_DISABLED,
      { project_id: projectId, source: "settings" },
    );
    updateMutation.mutate({
      projectId,
      status: on
        ? AGENT_INSIGHTS_JOB_STATUS.enabled
        : AGENT_INSIGHTS_JOB_STATUS.disabled,
    });
  };

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="2xs" aria-label="Diagnostics settings">
          <Settings2 className="mr-1.5 size-3" />
          Settings
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-[227px] p-1.5">
        <DropdownMenuItem
          size="sm"
          role="menuitemcheckbox"
          aria-checked={enabled}
          className="h-auto gap-2 p-1"
          disabled={updateMutation.isPending}
          onSelect={(e) => {
            e.preventDefault();
            handleToggle(!enabled);
          }}
        >
          <div className="flex min-w-0 flex-1 flex-col gap-px">
            <span className="comet-body-xs text-foreground">
              Automatic daily diagnostics
            </span>
            <span className="text-[10px] leading-3 text-muted-slate">
              Runs on Ollie credits
            </span>
          </div>
          <Switch
            size="2xs"
            checked={enabled}
            tabIndex={-1}
            aria-hidden
            className="pointer-events-none"
          />
        </DropdownMenuItem>
        {onEditGuidance && (
          <>
            <DropdownMenuSeparator className="mx-0 bg-border" />
            <DropdownMenuItem
              size="sm"
              className="comet-body-xs h-6 p-1"
              onSelect={onEditGuidance}
            >
              Edit project guidance
            </DropdownMenuItem>
          </>
        )}
        {BillingLink && (
          <DropdownMenuItem asChild>
            <BillingLink label="Manage billing" variant="menu" />
          </DropdownMenuItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DiagnosticsSettingsMenu;
