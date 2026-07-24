import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";
import OnlineEvaluationPage from "@/v2/pages/OnlineEvaluationPage/OnlineEvaluationPage";

const OnlineEvaluationPageGuard = () => {
  const {
    permissions: { canViewOnlineEvaluationRules },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewOnlineEvaluationRules}
      message="You don't have permissions to view online evaluation in this workspace."
    >
      <OnlineEvaluationPage />
    </NoAccessPageGuard>
  );
};

export default OnlineEvaluationPageGuard;
