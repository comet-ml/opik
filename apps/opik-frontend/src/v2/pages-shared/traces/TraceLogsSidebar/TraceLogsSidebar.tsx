import React, { useMemo, useState } from "react";
import { JsonParam, StringParam, useQueryParam } from "use-query-params";

import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { LOGS_SOURCE } from "@/types/traces";
import { Filter } from "@/types/filters";
import { COLUMN_EXPERIMENT_IDS } from "@/types/shared";
import TraceLogsView, {
  DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  TLS_QUERY_PREFIX,
  TraceLogsViewConfig,
} from "@/v2/pages-shared/traces/TraceLogsView/TraceLogsView";

type TraceLogsSidebarProps = {
  open: boolean;
  onClose: () => void;
  projectId: string;
  projectName?: string;
  logsSource?: LOGS_SOURCE;
  title?: string;
  viewConfig?: TraceLogsViewConfig;
  scopeTooltip?: string;
};

/**
 * Overlay host for the shared trace-logs view. Owns only the sheet: the locked scope arrives via the
 * shared tls_* params (written by the trigger buttons, so one page-level sidebar can serve many
 * per-row triggers), and the sheet's content element is handed to the view so the trace/thread
 * detail panels render inside the overlay rather than behind it.
 */
const TraceLogsSidebar: React.FunctionComponent<TraceLogsSidebarProps> = ({
  open,
  onClose,
  projectId,
  projectName = "",
  logsSource,
  title = "Logs",
  viewConfig = DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  scopeTooltip,
}) => {
  const [sheetContentRef, setSheetContentRef] = useState<HTMLDivElement | null>(
    null,
  );

  const [scopeFilters] = useQueryParam<Filter[] | undefined>(
    `${TLS_QUERY_PREFIX}scope`,
    JsonParam,
  );
  const [seededFilters] = useQueryParam<Filter[] | undefined>(
    `${TLS_QUERY_PREFIX}filters`,
    JsonParam,
  );

  // Links minted before the scope existed carry the experiment constraint in the editable filter
  // key. No chip can express experiment ids, so the chip bar drops it — and a bookmarked trial-log
  // URL would quietly widen to the whole project. Lift those filters into the scope instead.
  const effectiveScope = useMemo(() => {
    const scope = Array.isArray(scopeFilters) ? scopeFilters : [];
    const legacy = (Array.isArray(seededFilters) ? seededFilters : []).filter(
      (f) => f?.field === COLUMN_EXPERIMENT_IDS,
    );
    return scope.length || legacy.length ? [...scope, ...legacy] : undefined;
  }, [scopeFilters, seededFilters]);
  const [scopeLabel] = useQueryParam(
    `${TLS_QUERY_PREFIX}scopeLabel`,
    StringParam,
  );
  const [traceId] = useQueryParam(`${TLS_QUERY_PREFIX}trace`, StringParam);

  const handleOpenChange = (isOpen: boolean) => {
    if (!isOpen) {
      onClose();
    }
  };

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        ref={setSheetContentRef}
        className="flex w-screen flex-col shadow-none sm:max-w-full"
        header={<SheetTopBar variant="info" title={title} />}
        onEscapeKeyDown={(e) => {
          if (traceId) {
            e.preventDefault();
          }
        }}
      >
        <TraceLogsView
          projectId={projectId}
          projectName={projectName}
          logsSource={logsSource}
          viewConfig={viewConfig}
          scopeFilters={effectiveScope}
          scopeLabel={scopeLabel ?? undefined}
          scopeTooltip={scopeTooltip}
          container={sheetContentRef}
          enabled={open}
        />
      </SheetContent>
    </Sheet>
  );
};

export default TraceLogsSidebar;
