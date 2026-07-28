import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";
import AgentRunnerPage from "@/v2/pages/AgentRunnerPage/AgentRunnerPage";

const AgentPlaygroundPageGuard = () => {
  const {
    permissions: { canViewAgentPlayground },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewAgentPlayground}
      message="You don't have permissions to use agent playground in this workspace."
    >
      <AgentRunnerPage />
    </NoAccessPageGuard>
  );
};

export default AgentPlaygroundPageGuard;
