import React, { useLayoutEffect, useState } from "react";
import { AgentInsightsIssuesPage } from "@/types/signals";
import { ISSUES_LIST_ATTRIBUTE } from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";

// What the columns leave above and below themselves once stuck.
const STUCK_GAP_PX = 8;

const useColumnsOverflow = (
  columnsRef: React.RefObject<HTMLDivElement>,
  issues: AgentInsightsIssuesPage | undefined,
) => {
  const [overflows, setOverflows] = useState(false);

  useLayoutEffect(() => {
    const columns = columnsRef.current;
    const container = columns?.offsetParent;
    const list = columns?.querySelector(`[${ISSUES_LIST_ATTRIBUTE}]`);
    if (!columns || !(container instanceof HTMLElement) || !list) {
      setOverflows(false);
      return;
    }

    const measure = () =>
      setOverflows(
        list.scrollHeight >
          container.clientHeight - columns.offsetTop - STUCK_GAP_PX,
      );

    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(container);
    observer.observe(list);
    return () => observer.disconnect();
  }, [columnsRef, issues]);

  return overflows;
};

export default useColumnsOverflow;
