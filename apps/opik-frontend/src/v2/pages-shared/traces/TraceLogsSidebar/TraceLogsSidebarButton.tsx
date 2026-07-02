import React, { useCallback } from "react";
import { ListTree } from "lucide-react";

import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TraceLogsSidebar, { TraceLogsViewConfig } from "./TraceLogsSidebar";
import { useTraceLogsSidebarControls } from "./useTraceLogsSidebarControls";
import { LOGS_SOURCE } from "@/types/traces";
import { Filter } from "@/types/filters";

type TraceLogsSidebarButtonProps = {
  projectId: string;
  logsSource?: LOGS_SOURCE;
  sourceFilters?: Filter[];
  // When true, sourceFilters become a locked scope: applied to the query but not user-editable or
  // removable via the filter bar (e.g. the per-evaluator Evaluation traces sidebar). scopeLabel is
  // shown as a read-only indicator of what the view is locked to.
  lockScope?: boolean;
  scopeLabel?: string;
  variant?: "tag" | "icon";
  title?: string;
  label?: string;
  viewConfig?: TraceLogsViewConfig;
  // When false, render only the trigger and let a single page-level <TraceLogsSidebar /> handle
  // display. Used by per-row triggers (e.g. the online-evaluation rules table) so the sidebar is
  // mounted once for the page instead of once per row (which would race on the shared tls_* state).
  renderSidebar?: boolean;
};

const TraceLogsSidebarButton: React.FunctionComponent<
  TraceLogsSidebarButtonProps
> = ({
  projectId,
  logsSource,
  sourceFilters,
  lockScope = false,
  scopeLabel,
  variant = "tag",
  title,
  label = "Go to logs",
  viewConfig,
  renderSidebar = true,
}) => {
  const { open, openSidebar, closeSidebar } = useTraceLogsSidebarControls();

  const handleOpen = useCallback(
    () =>
      openSidebar(
        sourceFilters,
        lockScope ? { locked: true, label: scopeLabel } : undefined,
      ),
    [openSidebar, sourceFilters, lockScope, scopeLabel],
  );

  const trigger =
    variant === "icon" ? (
      <TooltipWrapper content={label}>
        <Button
          data-testid="playground-logs-sidebar-button"
          variant="outline"
          size="icon-2xs"
          onClick={handleOpen}
        >
          <ListTree />
        </Button>
      </TooltipWrapper>
    ) : (
      <TooltipWrapper content={label}>
        <Tag
          size="md"
          variant="transparent"
          className="flex shrink-0 cursor-pointer items-center gap-1 hover:bg-primary-foreground hover:text-foreground active:bg-primary-100 active:text-foreground"
          onClick={handleOpen}
        >
          <ListTree
            className="size-3 shrink-0"
            style={{ color: "var(--color-green)" }}
          />
          <div className="comet-body-s-accented truncate text-muted-slate">
            {label}
          </div>
        </Tag>
      </TooltipWrapper>
    );

  return (
    <>
      {trigger}
      {renderSidebar && (
        <TraceLogsSidebar
          open={open}
          onClose={closeSidebar}
          projectId={projectId}
          logsSource={logsSource}
          title={title}
          viewConfig={viewConfig}
        />
      )}
    </>
  );
};

export default TraceLogsSidebarButton;
