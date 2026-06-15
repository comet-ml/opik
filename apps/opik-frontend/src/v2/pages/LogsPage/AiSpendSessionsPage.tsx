import useProjectByName from "@/api/projects/useProjectByName";
import { useAiSpend } from "@/contexts/AiSpendContext";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import LogsTab from "@/v2/pages/LogsPage/LogsTab";
import useLogsType from "@/v2/pages/LogsPage/useLogsType";
import Loader from "@/shared/Loader/Loader";
import NoData from "@/shared/NoData/NoData";

const AiSpendSessionsPage = () => {
  const { projectName } = useAiSpend();
  const {
    data: project,
    isPending,
    isError,
  } = useProjectByName(
    { projectName },
    { refetchOnMount: false, retry: false },
  );

  const projectId = project?.id ?? "";

  const { logsType, needsDefaultResolution, setLogsType } = useLogsType({
    projectId,
  });

  if (isPending) {
    return <Loader />;
  }

  if (isError || !project) {
    return (
      <NoData
        title="No sessions yet"
        message="No Claude Code activity has been logged for this organization yet."
      />
    );
  }

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="mb-3 mt-6 flex items-center justify-between"
        direction="horizontal"
      >
        <h1 className="comet-body-accented truncate break-words">Sessions</h1>
      </PageBodyStickyContainer>
      {needsDefaultResolution ? (
        <Loader />
      ) : (
        <LogsTab
          projectId={projectId}
          projectName={projectName}
          logsType={logsType}
          onLogsTypeChange={setLogsType}
        />
      )}
    </PageBodyScrollContainer>
  );
};

export default AiSpendSessionsPage;
