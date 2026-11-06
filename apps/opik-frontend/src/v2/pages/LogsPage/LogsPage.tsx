import { useActiveProjectId } from "@/store/AppStore";
import useProjectById from "@/api/projects/useProjectById";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import LogsTab from "@/v2/pages/LogsPage/LogsTab";
import Loader from "@/shared/Loader/Loader";
import useLogsType from "@/v2/pages/LogsPage/useLogsType";
import {
  resolveProjectDateRangeConfig,
  ProjectDateRangeConfig,
} from "@/v2/pages-shared/traces/resolveProjectDateRangeConfig";

type LogsPageContentProps = {
  projectId: string;
  projectName: string;
  dateRangeConfig: ProjectDateRangeConfig;
};

const LogsPageContent: React.FunctionComponent<LogsPageContentProps> = ({
  projectId,
  projectName,
  dateRangeConfig,
}) => {
  // Every consumer of the shared date-range key is inside this component, so they all receive the
  // same already-settled config.
  const { logsType, needsDefaultResolution, setLogsType } = useLogsType({
    projectId,
    dateRangeConfig,
  });

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-3 mt-6 flex items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-body-accented truncate break-words">Logs</h1>
      </PageBodyStickyContainer>
      {needsDefaultResolution ? (
        <Loader />
      ) : (
        <LogsTab
          projectId={projectId}
          projectName={projectName}
          dateRangeConfig={dateRangeConfig}
          logsType={logsType}
          onLogsTypeChange={setLogsType}
        />
      )}
    </PageBodyScrollContainer>
  );
};

/**
 * Resolves the project before mounting anything that reads the date range.
 *
 * The date-range state is backed by use-local-storage-state, which captures its `defaultValue` once
 * (useState) and writes that captured value into storage. Anything mounting while the project name
 * is still unknown would freeze the workspace 30-day placeholder — and because the demo project's
 * storage key only gains its suffix once the name arrives, that stale default would be written into
 * the demo's own slot, where the tabs then read it. Gating the mount makes the captured value right
 * by construction. A failed lookup reports not-pending, so this cannot hang.
 */
const LogsPage = () => {
  const projectId = useActiveProjectId()!;
  const { data: project, isPending: isProjectPending } = useProjectById(
    { projectId },
    { refetchOnMount: false },
  );

  if (isProjectPending) {
    return <Loader />;
  }

  return (
    <LogsPageContent
      projectId={projectId}
      projectName={project?.name || projectId}
      // project?.name, not projectName — the fallback to the raw id would read as "not the demo
      // project".
      dateRangeConfig={resolveProjectDateRangeConfig(project?.name)}
    />
  );
};

export default LogsPage;
