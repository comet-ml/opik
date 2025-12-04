import NewQuickstart from "@/components/pages/GetStartedPage/NewQuickstart";
import useUser from "./useUser";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import { ORGANIZATION_PLAN_ENTERPRISE } from "./types";
import useAppStore from "@/store/AppStore";

const GetStartedPage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: user } = useUser();
  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  if (!user) return;

  const currentWorkspace = allWorkspaces?.find(
    (workspace) => workspace.workspaceName === workspaceName,
  );

  const currentOrganization = organizations?.find((org) => {
    return org.id === currentWorkspace?.organizationId;
  });

  const isEnterpriseCustomer =
    currentOrganization?.paymentPlan === ORGANIZATION_PLAN_ENTERPRISE;

  return <NewQuickstart shouldSkipQuestions={isEnterpriseCustomer} />;
};

export default GetStartedPage;
