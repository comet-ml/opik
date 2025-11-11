import React from "react";
import { useTranslation } from "react-i18next";
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
import AnnotationQueuesTab from "@/components/pages/TracesPage/AnnotationQueuesTab/AnnotationQueuesTab";
import { Button } from "@/components/ui/button";
import { Construction } from "lucide-react";
import { useState } from "react";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import SetGuardrailDialog from "../HomePageShared/SetGuardrailDialog";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const TracesPage = () => {
  const { t } = useTranslation();
  const projectId = useProjectIdFromURL();
  const [isGuardrailsDialogOpened, setIsGuardrailsDialogOpened] =
    useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

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

  const openGuardrailsDialog = () => setIsGuardrailsDialogOpened(true);

  return (
    <>
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
          {isGuardrailsEnabled && (
            <Button variant="outline" size="sm" onClick={openGuardrailsDialog}>
              <Construction className="mr-1.5 size-3.5" />
              {t("traces.setGuardrail")}
            </Button>
          )}
        </PageBodyStickyContainer>
        {project?.description && (
          <PageBodyStickyContainer
            className="-mt-3 mb-4 flex min-h-8 items-center justify-between"
            direction="horizontal"
          >
            <div className="text-muted-slate">{project.description}</div>
          </PageBodyStickyContainer>
        )}
        <Tabs
          defaultValue="traces"
          value={type as string}
          onValueChange={setType}
          className="min-w-min"
        >
          <PageBodyStickyContainer direction="horizontal" limitWidth>
            <TabsList variant="underline">
              <TabsTrigger variant="underline" value={TRACE_DATA_TYPE.traces}>
                {t("traces.tabs.traces")}
              </TabsTrigger>
              <TabsTrigger variant="underline" value={TRACE_DATA_TYPE.spans}>
                {t("traces.tabs.spans")}
              </TabsTrigger>
              <TabsTrigger variant="underline" value="threads">
                {t("traces.tabs.threads")}
              </TabsTrigger>
              <TabsTrigger variant="underline" value="metrics">
                {t("traces.tabs.metrics")}
              </TabsTrigger>
              <TabsTrigger variant="underline" value="rules">
                {t("traces.tabs.onlineEvaluation")}
              </TabsTrigger>
              <TabsTrigger variant="underline" value="annotation-queues">
                {t("traces.tabs.annotationQueues")}
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
          <TabsContent value={TRACE_DATA_TYPE.spans}>
            <TracesSpansTab
              type={TRACE_DATA_TYPE.spans}
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
          <TabsContent value="annotation-queues">
            <AnnotationQueuesTab projectId={projectId} />
          </TabsContent>
        </Tabs>
      </PageBodyScrollContainer>
      {isGuardrailsEnabled && (
        <SetGuardrailDialog
          open={isGuardrailsDialogOpened}
          setOpen={setIsGuardrailsDialogOpened}
          projectName={projectName}
        />
      )}
    </>
  );
};

export default TracesPage;
