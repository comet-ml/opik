import React, { useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Tag } from "@/ui/tag";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import { useClaudeCodeData } from "./useClaudeCodeData";
import OverviewTab from "./tabs/OverviewTab";
import PlaceholderTab from "./tabs/PlaceholderTab";

const TABS = [
  { id: "overview", label: "Overview" },
  { id: "teams", label: "Teams" },
  { id: "topics", label: "Topics" },
  { id: "spend", label: "Spend Analysis" },
  { id: "compliance", label: "Compliance" },
] as const;

type TabId = (typeof TABS)[number]["id"];

const ClaudeCodePage: React.FC = () => {
  const [tabParam, setTabParam] = useQueryParam("tab", StringParam);
  const activeTab = (
    TABS.some((t) => t.id === tabParam) ? tabParam : "overview"
  ) as TabId;
  const [windowDays, setWindowDays] = useState<7 | 30 | 90>(30);
  const data = useClaudeCodeData(windowDays);

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex items-center gap-3"
        direction="horizontal"
      >
        <h1 className="comet-title-l truncate break-words text-foreground">
          Coding Harness
        </h1>
        <Tag variant="purple" size="md">
          Org Admin
        </Tag>
      </PageBodyStickyContainer>

      <PageBodyStickyContainer
        className="pb-3"
        direction="bidirectional"
        limitWidth
      >
        <Tabs value={activeTab} onValueChange={(v) => setTabParam(v as TabId)}>
          <TabsList variant="underline" className="justify-start">
            {TABS.map((t) => (
              <TabsTrigger key={t.id} variant="underline" value={t.id}>
                {t.label}
              </TabsTrigger>
            ))}
          </TabsList>

          <div className="px-6 pb-10 pt-4">
            <TabsContent value="overview">
              <OverviewTab
                data={data}
                windowDays={windowDays}
                onWindowChange={setWindowDays}
                onNavigateCompliance={() => setTabParam("compliance")}
              />
            </TabsContent>
            <TabsContent value="teams">
              <PlaceholderTab title="Teams" />
            </TabsContent>
            <TabsContent value="topics">
              <PlaceholderTab title="Topics" />
            </TabsContent>
            <TabsContent value="spend">
              <PlaceholderTab title="Spend Analysis" />
            </TabsContent>
            <TabsContent value="compliance">
              <PlaceholderTab title="Compliance" />
            </TabsContent>
          </div>
        </Tabs>
      </PageBodyStickyContainer>
    </PageBodyScrollContainer>
  );
};

export default ClaudeCodePage;
