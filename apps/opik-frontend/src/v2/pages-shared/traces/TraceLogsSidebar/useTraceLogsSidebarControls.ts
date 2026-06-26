import { useCallback } from "react";
import {
  BooleanParam,
  JsonParam,
  StringParam,
  useQueryParam,
} from "use-query-params";

import { Filter } from "@/types/filters";
import { TLS_QUERY_PREFIX } from "./TraceLogsSidebar";

/**
 * Open/close + filter wiring for the trace-logs sidebar, backed by the shared `tls_*` query params.
 * Used by both the trigger button and a standalone page-level sidebar so a single sidebar instance
 * can be driven by many triggers (e.g. one per online-evaluation rule row) without each trigger
 * mounting its own sidebar.
 */
export const useTraceLogsSidebarControls = () => {
  const [open = false, setOpen] = useQueryParam(
    `${TLS_QUERY_PREFIX}open`,
    BooleanParam,
    { updateType: "replaceIn" },
  );
  const [, setTlsFilters] = useQueryParam(
    `${TLS_QUERY_PREFIX}filters`,
    JsonParam,
    { updateType: "replaceIn" },
  );
  const [, setTlsTrace] = useQueryParam(
    `${TLS_QUERY_PREFIX}trace`,
    StringParam,
    {
      updateType: "replaceIn",
    },
  );
  const [, setTlsSpan] = useQueryParam(`${TLS_QUERY_PREFIX}span`, StringParam, {
    updateType: "replaceIn",
  });

  const openSidebar = useCallback(
    (sourceFilters?: Filter[]) => {
      if (sourceFilters?.length) {
        setTlsFilters(sourceFilters);
      }
      setOpen(true);
    },
    [setTlsFilters, setOpen],
  );

  const closeSidebar = useCallback(() => {
    setOpen(undefined);
    setTlsFilters(undefined);
    setTlsTrace(undefined);
    setTlsSpan(undefined);
  }, [setOpen, setTlsFilters, setTlsTrace, setTlsSpan]);

  return { open: Boolean(open), openSidebar, closeSidebar };
};
