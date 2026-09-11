import { useActiveProjectId } from "@/store/AppStore";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import AgentRunnerContent from "./AgentRunnerContent";

const AgentRunnerPage = () => {
  const projectId = useActiveProjectId()!;

  return (
    <PageBodyScrollContainer>
      <AgentRunnerContent projectId={projectId} />
    </PageBodyScrollContainer>
  );
};

export default AgentRunnerPage;
