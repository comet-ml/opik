import React from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { Inbox, Radar } from "lucide-react";
import { useActiveProjectId } from "@/store/AppStore";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Button } from "@/ui/button";
import { getTimeFromNow } from "@/lib/date";
import useSignalsStats from "@/api/signals/useSignalsStats";
import SignalsStatsCards from "@/v2/pages/SignalsPage/SignalsStatsCards";
import IssuesTab from "@/v2/pages/SignalsPage/IssuesTab/IssuesTab";
import PatternsTab from "@/v2/pages/SignalsPage/PatternsTab";

const DEFAULT_TAB = "issues";

const SignalsPage: React.FC = () => {
  const projectId = useActiveProjectId()!;
  const [tab = DEFAULT_TAB, setTab] = useQueryParam("tab", StringParam, {
    updateType: "replaceIn",
  });

  const { data: stats, isPending: isStatsPending } = useSignalsStats({
    projectId,
  });

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-title-l truncate break-words">Signals</h1>
      </PageBodyStickyContainer>

      <Tabs value={tab ?? DEFAULT_TAB} onValueChange={setTab}>
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="segmented-primary">
            <TabsTrigger variant="segmented-primary" value="issues">
              Issues
            </TabsTrigger>
            <TabsTrigger variant="segmented-primary" value="patterns">
              Patterns
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>

        <div className="mt-4 flex flex-col gap-4 px-6 pb-6">
          <SignalsStatsCards data={stats} isPending={isStatsPending} />

          <div className="flex items-center justify-between">
            <span className="comet-body-xs text-muted-slate">
              {stats
                ? `Last scan: ${getTimeFromNow(stats.last_scan_at)}`
                : "Last scan: —"}
            </span>
            <div className="flex items-center gap-2">
              <Button variant="outline" size="xs">
                <Inbox className="mr-1.5 size-3.5" />
                Archive
              </Button>
              <Button variant="outline" size="xs">
                <Radar className="mr-1.5 size-3.5" />
                Run diagnostic
              </Button>
            </div>
          </div>

          <TabsContent value="issues" className="mt-0">
            <IssuesTab projectId={projectId} />
          </TabsContent>
          <TabsContent value="patterns" className="mt-0">
            <PatternsTab />
          </TabsContent>
        </div>
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default SignalsPage;
