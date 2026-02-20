import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useProjectById from "@/api/projects/useProjectById";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import LogsTab from "@/components/pages/TracesPage/LogsTab/LogsTab";
import MetricsTab from "@/components/pages/TracesPage/MetricsTab/MetricsTab";
import RulesTab from "@/components/pages/TracesPage/RulesTab/RulesTab";
import AnnotationQueuesTab from "@/components/pages/TracesPage/AnnotationQueuesTab/AnnotationQueuesTab";
import DashboardsTab from "@/components/pages/TracesPage/DashboardsTab/DashboardsTab";
import Loader from "@/components/shared/Loader/Loader";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Construction } from "lucide-react";
import { useState } from "react";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import SetGuardrailDialog from "../HomePageShared/SetGuardrailDialog";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import ViewSelector from "@/components/pages-shared/dashboards/ViewSelector";
import { VIEW_TYPE } from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";
import useProjectTabs from "@/components/pages/TracesPage/useProjectTabs";
import { PROJECT_TAB } from "@/constants/traces";
import usePluginsStore from "@/store/PluginsStore";
import useViewQueryParam from "@/components/pages-shared/dashboards/ViewSelector/hooks/useViewQueryParam";

const TracesPage = () => {
  const projectId = useProjectIdFromURL();
  const [isGuardrailsDialogOpened, setIsGuardrailsDialogOpened] =
    useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const DashboardsViewGuard = usePluginsStore(
    (state) => state.DashboardsViewGuard,
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

  const { view, setView } = useViewQueryParam();

  const openGuardrailsDialog = () => setIsGuardrailsDialogOpened(true);

  const renderContent = () => {
    if (view === VIEW_TYPE.DETAILS) {
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
              <TabsTrigger variant="underline" value={PROJECT_TAB.metrics}>
                Metrics
              </TabsTrigger>
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
          <TabsContent value={PROJECT_TAB.metrics}>
            <MetricsTab projectId={projectId} />
          </TabsContent>
          <TabsContent value={PROJECT_TAB.evaluators}>
            <RulesTab projectId={projectId} />
          </TabsContent>
          <TabsContent value={PROJECT_TAB.annotationQueues}>
            <AnnotationQueuesTab projectId={projectId} />
          </TabsContent>
        </Tabs>
      );
    }

    if (view === VIEW_TYPE.DASHBOARDS) {
      return <DashboardsTab projectId={projectId} />;
    }

    return null;
  };

  return (
    <>
      {DashboardsViewGuard && (
        <DashboardsViewGuard view={view} setView={setView} />
      )}
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
          <div className="flex shrink-0 items-center gap-2">
            {isGuardrailsEnabled && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={openGuardrailsDialog}
                >
                  <Construction className="mr-1.5 size-3.5" />
                  Set a guardrail
                </Button>
                <Separator orientation="vertical" className="h-4" />
              </>
            )}
            <ViewSelector value={view as VIEW_TYPE} onChange={setView} />
          </div>
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
