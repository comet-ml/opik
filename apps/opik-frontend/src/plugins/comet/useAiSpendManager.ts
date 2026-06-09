import useAppStore from "@/store/AppStore";
import useAllWorkspaces from "./useAllWorkspaces";
import useOrganizations from "./useOrganizations";
import useUser from "./useUser";
import { isAiSpendWorkspace } from "./lib/aiSpend";
import { ORGANIZATION_PLAN_ENTERPRISE, ORGANIZATION_ROLE_TYPE } from "./types";

const useAiSpendManager = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { data: user } = useUser();
  const { data: organizations, isPending: isOrganizationsPending } =
    useOrganizations({ enabled: !!user?.loggedIn });
  const { data: allWorkspaces, isPending: isWorkspacesPending } =
    useAllWorkspaces({ enabled: !!user?.loggedIn });

  const currentWorkspace = allWorkspaces?.find(
    (w) => w.workspaceName === workspaceName,
  );
  const organization = organizations?.find(
    (org) => org.id === currentWorkspace?.organizationId,
  );

  const spendWorkspace = allWorkspaces?.find(
    (w) => w.organizationId === organization?.id && isAiSpendWorkspace(w),
  );

  const isEnterprise =
    organization?.paymentPlan === ORGANIZATION_PLAN_ENTERPRISE;
  const isOrganizationAdmin =
    organization?.role === ORGANIZATION_ROLE_TYPE.admin;

  return {
    isPending: isOrganizationsPending || isWorkspacesPending,
    organization,
    spendWorkspace,
    spendWorkspaceName: spendWorkspace?.workspaceName,
    isEnterprise,
    isOrganizationAdmin,
    hasAccess: Boolean(spendWorkspace) && isEnterprise && isOrganizationAdmin,
  };
};

export default useAiSpendManager;
