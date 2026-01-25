import { StringParam, useQueryParam } from "use-query-params";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import useProjectById from "@/api/projects/useProjectById";
import useThreadsStatistic from "@/api/traces/useThreadsStatistic";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import LogsTab from "@/components/pages/TracesPage/LogsTab/LogsTab";
import MetricsTab from "@/components/pages/TracesPage/MetricsTab/MetricsTab";
import RulesTab from "@/components/pages/TracesPage/RulesTab/RulesTab";
import AnnotationQueuesTab from "@/components/pages/TracesPage/AnnotationQueuesTab/AnnotationQueuesTab";
import DashboardsTab from "@/components/pages/TracesPage/DashboardsTab/DashboardsTab";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Construction } from "lucide-react";
import { useState } from "react";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import SetGuardrailDialog from "../HomePageShared/SetGuardrailDialog";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import ViewSelector, {
  VIEW_TYPE,
} from "@/components/pages-shared/dashboards/ViewSelector/ViewSelector";
import { LOGS_TYPE } from "@/constants/traces";
import { STATISTIC_AGGREGATION_TYPE } from "@/types/shared";

enum PROJECT_TAB {
  logs = "logs",
  metrics = "metrics",
  evaluators = "rules",
  annotationQueues = "annotation-queues",
}

const TracesPage = () => {
  const projectId = useProjectIdFromURL();
  const [isGuardrailsDialogOpened, setIsGuardrailsDialogOpened] =
    useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
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

  // Fetch thread statistics to get thread count for determining default logs type
  const { data: threadsStats } = useThreadsStatistic(
    {
      projectId,
    },
    {
      enabled: !!projectId,
      refetchOnMount: false,
    },
  );

  // Extract thread count from statistics
  const threadCountStat = threadsStats?.stats?.find(
    (stat) =>
      stat.name === "thread_count" &&
      stat.type === STATISTIC_AGGREGATION_TYPE.COUNT,
  );
  const threadCount =
    (threadCountStat?.type === STATISTIC_AGGREGATION_TYPE.COUNT
      ? threadCountStat.value
      : 0) ?? 0;
  const defaultLogsType =
    threadCount > 0 ? LOGS_TYPE.threads : LOGS_TYPE.traces;

  // Remember the last selected LOGS_TYPE to preserve user selection across tab switches
  const [lastLogsType, setLastLogsType] = useState<LOGS_TYPE | null>(null);

  const [type = defaultLogsType, setType] = useQueryParam("type", StringParam, {
    updateType: "replaceIn",
  });

  // Determine which main tab is active based on the type value
  const activeTab = Object.values(PROJECT_TAB).includes(type as PROJECT_TAB)
    ? (type as PROJECT_TAB)
    : PROJECT_TAB.logs;

  // Handle tab change - preserve user's last selected logs type when returning to Logs tab
  const handleTabChange = (newTab: string) => {
    if (newTab === PROJECT_TAB.logs) {
      // Use remembered value if available, otherwise use default
      setType(lastLogsType ?? defaultLogsType);
    } else {
      // Remember current logs type before switching away
      if (Object.values(LOGS_TYPE).includes(type as LOGS_TYPE)) {
        setLastLogsType(type as LOGS_TYPE);
      }
      setType(newTab);
    }
  };

  const [view = VIEW_TYPE.DETAILS, setView] = useQueryParam(
    "view",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

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
              defaultLogsType={defaultLogsType}
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
        {renderContent()}
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
