import React, { useMemo, useState } from "react";
import HomeSummaryCards from "./HomeSummaryCards";
import SpendPeriodSelect from "./SpendPeriodSelect";
import AiUsageBreakdown from "@/v2/pages-shared/AiUsageBreakdown/AiUsageBreakdown";
import {
  AI_SPEND_PROJECT_NAME,
  getSpendInterval,
  SpendWindow,
} from "@/lib/aiSpend";

const AiSpendHomePage: React.FC = () => {
  const [windowDays, setWindowDays] = useState<SpendWindow>(30);

  const { intervalStart, intervalEnd } = useMemo(
    () => getSpendInterval(windowDays),
    [windowDays],
  );

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="comet-title-l truncate break-words text-foreground">
          Home
        </h1>
        <SpendPeriodSelect value={windowDays} onChange={setWindowDays} />
      </div>

      <HomeSummaryCards windowDays={windowDays} />

      <AiUsageBreakdown
        projectName={AI_SPEND_PROJECT_NAME}
        intervalStart={intervalStart}
        intervalEnd={intervalEnd}
        className="mt-4"
      />
    </div>
  );
};

export default AiSpendHomePage;
