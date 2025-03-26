import React from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useProjectById from "@/api/projects/useProjectById";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import TracesSpansTab from "@/components/pages/TracesPage/TracesSpansTab/TracesSpansTab";
import ThreadsTab from "@/components/pages/TracesPage/ThreadsTab/ThreadsTab";
import MetricsTab from "@/components/pages/TracesPage/MetricsTab/MetricsTab";
import RulesTab from "@/components/pages/TracesPage/RulesTab/RulesTab";

const TracesPage = () => {
  const projectId = useProjectIdFromURL();

  const [type = TRACE_DATA_TYPE.traces, setType] = useQueryParam(
    "type",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const { data: project } = useProjectById(
    {
      projectId,
    },
    {
      refetchOnMount: false,
    },
  );

  const projectName = project?.name || projectId;

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-4 mt-6 flex items-center justify-between"
        direction="horizontal"
      >
        <h1
          data-testid="traces-page-title"
          className="comet-title-l truncate break-words"
        >
          {projectName}
        </h1>
      </PageBodyStickyContainer>
      <Tabs
        defaultValue="traces"
        value={type as string}
        onValueChange={setType}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value={TRACE_DATA_TYPE.traces}>
              Traces
            </TabsTrigger>
            <TabsTrigger variant="underline" value={TRACE_DATA_TYPE.llm}>
              LLM calls
            </TabsTrigger>
            <TabsTrigger variant="underline" value="threads">
              Threads
            </TabsTrigger>
            <TabsTrigger variant="underline" value="metrics">
              Metrics
            </TabsTrigger>
            <TabsTrigger variant="underline" value="rules">
              Online evaluation
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value={TRACE_DATA_TYPE.traces}>
          <TracesSpansTab
            type={TRACE_DATA_TYPE.traces}
            projectId={projectId}
            projectName={projectName}
          />
        </TabsContent>
        <TabsContent value={TRACE_DATA_TYPE.llm}>
          <TracesSpansTab
            type={TRACE_DATA_TYPE.llm}
            projectId={projectId}
            projectName={projectName}
          />
        </TabsContent>
        <TabsContent value="threads">
          <ThreadsTab projectId={projectId} projectName={projectName} />
        </TabsContent>
        <TabsContent value="metrics">
          <MetricsTab projectId={projectId} />
        </TabsContent>
        <TabsContent value="rules">
          <RulesTab projectId={projectId} />
        </TabsContent>
      </Tabs>
    </PageBodyScrollContainer>
  );
};

export default TracesPage;
