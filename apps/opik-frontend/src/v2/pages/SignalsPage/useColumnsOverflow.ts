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
    if (!columns || !(container instanceof HTMLElement)) {
      setOverflows(false);
      return;
    }

    // IssuesTab renders the list from its own query, so it can arrive after this ran: resolve it per
    // measurement and watch the subtree, or a list that mounts late is never measured.
    const measure = () => {
      const list = columns.querySelector(`[${ISSUES_LIST_ATTRIBUTE}]`);
      setOverflows(
        !!list &&
          list.scrollHeight >
            container.clientHeight - columns.offsetTop - STUCK_GAP_PX,
      );
    };

    measure();
    const sizeObserver = new ResizeObserver(measure);
    sizeObserver.observe(container);
    const treeObserver = new MutationObserver(measure);
    treeObserver.observe(columns, { childList: true, subtree: true });

    return () => {
      sizeObserver.disconnect();
      treeObserver.disconnect();
    };
  }, [columnsRef, issues]);

  return overflows;
};

export default useColumnsOverflow;
