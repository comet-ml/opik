import React, { useState } from "react";
import HomeSummaryCards from "./HomeSummaryCards";
import SpendPeriodSelect from "./SpendPeriodSelect";
import { SpendWindow } from "@/lib/aiSpend";

const AiSpendHomePage: React.FC = () => {
  const [windowDays, setWindowDays] = useState<SpendWindow>(30);

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h1 className="comet-title-l truncate break-words text-foreground">
          Home
        </h1>
        <SpendPeriodSelect value={windowDays} onChange={setWindowDays} />
      </div>

      <HomeSummaryCards windowDays={windowDays} />
    </div>
  );
};

export default AiSpendHomePage;
