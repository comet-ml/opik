import React from "react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { AGENT_INSIGHTS_JOB_STATUS } from "@/types/signals";
import useUpdateAgentInsightsJobMutation from "@/api/signals/useUpdateAgentInsightsJobMutation";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";

const TAG_CLASS =
  "comet-body-xs-accented inline-flex h-6 shrink-0 items-center gap-1.5 rounded-md border border-border bg-primary-foreground px-2 text-foreground";

type AutoRunToggleProps = {
  projectId: string;
  enabled: boolean;
  canConfigure: boolean;
};

const AutoRunToggle: React.FC<AutoRunToggleProps> = ({
  projectId,
  enabled,
  canConfigure,
}) => {
  const updateMutation = useUpdateAgentInsightsJobMutation();

  const content = (
    <>
      <span
        className={cn(
          "size-1.5 rounded-full",
          enabled ? "bg-success" : "bg-light-slate",
        )}
      />
      Auto-run {enabled ? "on" : "off"}
    </>
  );

  if (!canConfigure) {
    return <span className={TAG_CLASS}>{content}</span>;
  }

  const handleToggle = () => {
    trackEvent(
      enabled
        ? OpikEvent.DIAGNOSTICS_AUTO_DISABLED
        : OpikEvent.DIAGNOSTICS_AUTO_ENABLED,
      { project_id: projectId, source: "header" },
    );
    updateMutation.mutate({
      projectId,
      status: enabled
        ? AGENT_INSIGHTS_JOB_STATUS.disabled
        : AGENT_INSIGHTS_JOB_STATUS.enabled,
    });
  };

  return (
    <TooltipWrapper
      content={
        enabled
          ? "Click to turn off daily auto-run"
          : "Click to turn on daily auto-run"
      }
    >
      <button
        type="button"
        onClick={handleToggle}
        disabled={updateMutation.isPending}
        aria-pressed={enabled}
        className={cn(
          TAG_CLASS,
          "transition-colors hover:bg-primary-100 disabled:opacity-60",
        )}
      >
        {content}
      </button>
    </TooltipWrapper>
  );
};

export default AutoRunToggle;
