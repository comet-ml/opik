import React, { useMemo, useState } from "react";
import { AxiosError } from "axios";
import HomeSummaryCards from "./HomeSummaryCards";
import AiSpendEmptyState from "./AiSpendEmptyState";
import SpendPeriodSelect from "@/v2/pages-shared/SpendPeriodSelect/SpendPeriodSelect";
import AiUsageBreakdown from "@/v2/pages-shared/AiUsageBreakdown/AiUsageBreakdown";
import AiUsageRecommendations from "@/v2/pages-shared/AiUsageRecommendations/AiUsageRecommendations";
import AiUsageLaneDetailsPanel from "@/v2/pages-shared/AiUsageLaneDetails/AiUsageLaneDetailsPanel";
import useProjectByName from "@/api/projects/useProjectByName";
import { useAiSpend } from "@/contexts/AiSpendContext";
import { getSpendInterval, SpendWindow } from "@/lib/aiSpend";

const AiSpendHomePage: React.FC = () => {
  const { projectName } = useAiSpend();
  const [windowDays, setWindowDays] = useState<SpendWindow>(30);
  const [detailsLaneKey, setDetailsLaneKey] = useState<string | null>(null);
  const [highlightedLaneKey, setHighlightedLaneKey] = useState<string | null>(
    null,
  );

  const { intervalStart, intervalEnd } = useMemo(
    () => getSpendInterval(windowDays),
    [windowDays],
  );

  const {
    data: project,
    isPending: isProbePending,
    isError,
    error,
  } = useProjectByName(
    { projectName },
    { refetchOnMount: false, retry: false },
  );

  const isProjectMissing =
    isError && error instanceof AxiosError && error.response?.status === 404;
  const isRealError = isError && !isProjectMissing;
  const showEmptyState =
    !isProbePending && !isRealError && !project?.last_updated_trace_at;

  return (
    <div className="py-6">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="comet-title-l truncate break-words text-foreground">
          Home
        </h1>
        {!showEmptyState && (
          <SpendPeriodSelect value={windowDays} onChange={setWindowDays} />
        )}
      </div>

      {showEmptyState ? (
        <AiSpendEmptyState />
      ) : (
        <>
          <HomeSummaryCards
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
          />

          <h2 className="comet-body-accented mb-2 mt-6 text-foreground">
            AI usage breakdown
          </h2>
          <AiUsageBreakdown
            projectName={projectName}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            onLaneClick={setDetailsLaneKey}
            activeLaneKey={detailsLaneKey}
            highlightedLaneKey={highlightedLaneKey}
          />

          <AiUsageRecommendations
            projectName={projectName}
            intervalStart={intervalStart}
            intervalEnd={intervalEnd}
            onHoverRecommendation={setHighlightedLaneKey}
            className="mt-6"
          />

          <AiUsageLaneDetailsPanel
            laneKey={detailsLaneKey}
            projectName={projectName}
            defaultWindow={windowDays}
            onClose={() => setDetailsLaneKey(null)}
          />
        </>
      )}
    </div>
  );
};

export default AiSpendHomePage;
