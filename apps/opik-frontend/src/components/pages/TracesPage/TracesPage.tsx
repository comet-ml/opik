import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useProjectById from "@/api/projects/useProjectById";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import LogsTab from "@/components/pages/TracesPage/LogsTab/LogsTab";
import InsightsTab from "@/components/pages/TracesPage/InsightsTab/InsightsTab";
import RulesTab from "@/components/pages/TracesPage/RulesTab/RulesTab";
import AnnotationQueuesTab from "@/components/pages/TracesPage/AnnotationQueuesTab/AnnotationQueuesTab";
import ConfigurationTab from "@/components/pages/TracesPage/ConfigurationTab/ConfigurationTab";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import { Construction } from "lucide-react";
import { useState } from "react";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import SetGuardrailDialog from "../HomePageShared/SetGuardrailDialog";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import useProjectTabs from "@/components/pages/TracesPage/useProjectTabs";
import { PROJECT_TAB } from "@/constants/traces";

const TracesPage = () => {
  const projectId = useProjectIdFromURL();
  const [isGuardrailsDialogOpened, setIsGuardrailsDialogOpened] =
    useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );
  const isAgentConfigurationEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.AGENT_CONFIGURATION_ENABLED,
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

  const {
    activeTab,
    logsType,
    needsDefaultResolution,
    setLogsType,
    handleTabChange,
  } = useProjectTabs({
    projectId,
  });

  const openGuardrailsDialog = () => setIsGuardrailsDialogOpened(true);

  const renderContent = () => {
    return (
      <Tabs
        defaultValue={PROJECT_TAB.logs}
        value={activeTab}
        onValueChange={handleTabChange}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value={PROJECT_TAB.logs}>
              Logs
            </TabsTrigger>
            <TabsTrigger variant="underline" value={PROJECT_TAB.insights}>
              Insights
            </TabsTrigger>
            {isAgentConfigurationEnabled && (
              <TabsTrigger
                variant="underline"
                value={PROJECT_TAB.configuration}
              >
                Configuration
              </TabsTrigger>
            )}
            <TabsTrigger variant="underline" value={PROJECT_TAB.evaluators}>
              Online evaluation
            </TabsTrigger>
            <TabsTrigger
              variant="underline"
              value={PROJECT_TAB.annotationQueues}
            >
              Annotation queues
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value={PROJECT_TAB.logs}>
          <LogsTab
            projectId={projectId}
            projectName={projectName}
            logsType={logsType}
            onLogsTypeChange={setLogsType}
          />
        </TabsContent>
        <TabsContent value={PROJECT_TAB.insights}>
          <InsightsTab projectId={projectId} />
        </TabsContent>
        {isAgentConfigurationEnabled && (
          <TabsContent value={PROJECT_TAB.configuration}>
            <ConfigurationTab projectId={projectId} />
          </TabsContent>
        )}
        <TabsContent value={PROJECT_TAB.evaluators}>
          <RulesTab projectId={projectId} />
        </TabsContent>
        <TabsContent value={PROJECT_TAB.annotationQueues}>
          <AnnotationQueuesTab projectId={projectId} />
        </TabsContent>
      </Tabs>
    );
  };

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
            <div className="flex shrink-0 items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={openGuardrailsDialog}
              >
                <Construction className="mr-1.5 size-3.5" />
                Set a guardrail
              </Button>
            </div>
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
        {needsDefaultResolution ? <Loader /> : renderContent()}
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
