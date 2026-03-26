import { useActiveProjectId } from "@/store/AppStore";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import AgentConfigurationTab from "@/v2/pages/AgentConfigurationPage/AgentConfigurationTab/AgentConfigurationTab";

const AgentConfigurationPage = () => {
  const projectId = useActiveProjectId()!;

  return (
    <PageBodyScrollContainer>
      <AgentConfigurationTab projectId={projectId} />
    </PageBodyScrollContainer>
  );
};

export default AgentConfigurationPage;
