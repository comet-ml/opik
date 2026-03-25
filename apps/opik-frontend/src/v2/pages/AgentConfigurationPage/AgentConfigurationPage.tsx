import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import PageBodyScrollContainer from "@/v2/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import AgentConfigurationTab from "@/v2/pages/AgentConfigurationPage/AgentConfigurationTab/AgentConfigurationTab";

const AgentConfigurationPage = () => {
  const projectId = useProjectIdFromURL();

  return (
    <PageBodyScrollContainer>
      <AgentConfigurationTab projectId={projectId} />
    </PageBodyScrollContainer>
  );
};

export default AgentConfigurationPage;
