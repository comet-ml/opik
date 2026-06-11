import React, { useMemo, useState } from "react";
import HomeSummaryCards from "./HomeSummaryCards";
import SpendPeriodSelect from "@/v2/pages-shared/SpendPeriodSelect/SpendPeriodSelect";
import AiUsageBreakdown from "@/v2/pages-shared/AiUsageBreakdown/AiUsageBreakdown";
import AiUsageRecommendations from "@/v2/pages-shared/AiUsageRecommendations/AiUsageRecommendations";
import AiUsageLaneDetailsPanel from "@/v2/pages-shared/AiUsageLaneDetails/AiUsageLaneDetailsPanel";
import {
  AI_SPEND_PROJECT_NAME,
  getSpendInterval,
  SpendWindow,
} from "@/lib/aiSpend";

const AiSpendHomePage: React.FC = () => {
  const [windowDays, setWindowDays] = useState<SpendWindow>(30);
  const [detailsLaneKey, setDetailsLaneKey] = useState<string | null>(null);
  const [highlightedLaneKey, setHighlightedLaneKey] = useState<string | null>(
    null,
  );

  const { intervalStart, intervalEnd } = useMemo(
    () => getSpendInterval(windowDays),
    [windowDays],
  );

  return (
    <div className="py-6">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="comet-title-l truncate break-words text-foreground">
          Home
        </h1>
        <SpendPeriodSelect value={windowDays} onChange={setWindowDays} />
      </div>

      <HomeSummaryCards
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
      />

      <h2 className="comet-body-accented mb-2 mt-6 text-foreground">
        AI usage breakdown
      </h2>
      <AiUsageBreakdown
        projectName={AI_SPEND_PROJECT_NAME}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        onLaneClick={setDetailsLaneKey}
        activeLaneKey={detailsLaneKey}
        highlightedLaneKey={highlightedLaneKey}
      />

      <AiUsageRecommendations
        projectName={AI_SPEND_PROJECT_NAME}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        onHoverRecommendation={setHighlightedLaneKey}
        className="mt-6"
      />

      <AiUsageLaneDetailsPanel
        laneKey={detailsLaneKey}
        projectName={AI_SPEND_PROJECT_NAME}
        defaultWindow={windowDays}
        onClose={() => setDetailsLaneKey(null)}
      />
    </div>
  );
};

export default AiSpendHomePage;
