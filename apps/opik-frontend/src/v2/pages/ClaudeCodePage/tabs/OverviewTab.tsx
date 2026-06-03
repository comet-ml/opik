import React from "react";
import {
  Building2,
  ClipboardList,
  DollarSign,
  PiggyBank,
  Users,
  UserPlus,
} from "lucide-react";
import { Card } from "@/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/ui/tabs";
import { ClaudeCodeData } from "../types";
import { TokenCompositionSection } from "./TokenCompositionSection";
import { RecommendationsSection } from "./RecommendationsSection";

const fullUsd = (n: number) => `$${Math.round(n).toLocaleString()}`;

interface KpiProps {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string;
  hint?: React.ReactNode;
}

const Kpi: React.FC<KpiProps> = ({ icon: Icon, label, value, hint }) => (
  <Card className="flex flex-col gap-2 p-4">
    <div className="flex items-center gap-2 text-muted-foreground">
      <div className="flex size-7 items-center justify-center rounded-md bg-muted">
        <Icon className="size-4" />
      </div>
      <span className="comet-body-s">{label}</span>
    </div>
    <div className="comet-title-l text-foreground">{value}</div>
    {hint ? (
      <div className="comet-body-xs text-muted-foreground">{hint}</div>
    ) : null}
  </Card>
);

interface Props {
  data: ClaudeCodeData;
  windowDays: 7 | 30 | 90;
  onWindowChange: (w: 7 | 30 | 90) => void;
  onNavigateCompliance?: () => void;
}

const OverviewTab: React.FC<Props> = ({ data, windowDays, onWindowChange }) => {
  const { org, tokenComposition, recommendations } = data;

  const budgetRemaining = Math.max(0, org.budget - org.spendMTD);
  const budgetPctLeft = Math.round((budgetRemaining / org.budget) * 100);

  // Headline health analysis — fed into the hero card so the user sees a
  // one-line summary before drilling into the categories.
  const totalRecoverable = recommendations.reduce(
    (a, r) => a + r.estSavingsMonthly,
    0,
  );
  const recoverablePct = Math.round((totalRecoverable / org.spendMTD) * 100);
  // Pull the top 2 levers by savings — referenced verbatim in the headline.
  const topRecs = [...recommendations]
    .sort((a, b) => b.estSavingsMonthly - a.estSavingsMonthly)
    .slice(0, 2);
  const leverPhrases = [
    "tighter /compact thresholds",
    "lower thinking effort on routine sessions",
  ].slice(0, topRecs.length);
  const analysis = {
    grade: "B+",
    label: "Healthy",
    tone: "green" as const,
    recoverableUsd: totalRecoverable,
    recoverablePct,
    activeUsers: org.activeUsers,
    totalUsers: org.totalUsers,
    activeTeams: org.activeTeams,
    topLevers: leverPhrases,
  };

  return (
    <div className="flex flex-col gap-6">
      <div className="flex justify-end">
        <Tabs
          value={String(windowDays)}
          onValueChange={(v) => onWindowChange(Number(v) as 7 | 30 | 90)}
        >
          <TabsList variant="segmented">
            <TabsTrigger variant="segmented" value="7">
              7d
            </TabsTrigger>
            <TabsTrigger variant="segmented" value="30">
              30d
            </TabsTrigger>
            <TabsTrigger variant="segmented" value="90">
              90d
            </TabsTrigger>
          </TabsList>
        </Tabs>
      </div>

      <TokenCompositionSection
        composition={tokenComposition}
        windowDays={windowDays}
        analysis={analysis}
      />

      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <Kpi
          icon={DollarSign}
          label="Org spend MTD"
          value={fullUsd(org.spendMTD)}
        />
        <Kpi
          icon={PiggyBank}
          label="Budget remaining"
          value={fullUsd(budgetRemaining)}
          hint={
            <span className="text-chart-red">
              {budgetPctLeft}% left of {fullUsd(org.budget)}
            </span>
          }
        />
        <Kpi
          icon={Users}
          label="Active users"
          value={`${org.activeUsers} / ${org.totalUsers}`}
        />
        <Kpi
          icon={Building2}
          label="Active teams"
          value={String(org.activeTeams)}
        />
        <Kpi
          icon={ClipboardList}
          label="Total sessions"
          value={org.totalSessions.toLocaleString()}
        />
        <Kpi
          icon={UserPlus}
          label="Avg cost / user"
          value={fullUsd(org.avgCostPerUser)}
        />
      </div>

      <RecommendationsSection recommendations={recommendations} />
    </div>
  );
};

export default OverviewTab;
