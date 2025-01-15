import React from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useProjectById from "@/api/projects/useProjectById";
import TracesSpansTab from "@/components/pages/TracesPage/TracesSpansTab";
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
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1
          data-testid="traces-page-title"
          className="comet-title-l truncate break-words"
        >
          {projectName}
        </h1>
      </div>
      <Tabs
        defaultValue="traces"
        value={type as string}
        onValueChange={setType}
      >
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value={TRACE_DATA_TYPE.traces}>
            Traces
          </TabsTrigger>
          <TabsTrigger variant="underline" value={TRACE_DATA_TYPE.llm}>
            LLM calls
          </TabsTrigger>
          <TabsTrigger variant="underline" value="metrics">
            Metrics
          </TabsTrigger>
          <TabsTrigger variant="underline" value="rules">
            Rules
          </TabsTrigger>
        </TabsList>
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
        <TabsContent value="metrics">
          <MetricsTab projectId={projectId} />
        </TabsContent>
        <TabsContent value="rules">
          <RulesTab projectId={projectId} />
        </TabsContent>
      </Tabs>
    </div>
  );
};

export default TracesPage;
