import useAppStore, { useWorkspaceVersion } from "@/store/AppStore";
import useOrganizations from "./useOrganizations";
import useUser from "./useUser";
import useWorkspaceByName from "./useWorkspaceByName";
import { isAiSpendWorkspace } from "./lib/aiSpend";
import { ORGANIZATION_PLAN_ENTERPRISE, ORGANIZATION_ROLE_TYPE } from "./types";

const useAiSpendManager = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const workspaceVersion = useWorkspaceVersion();

  const { data: user } = useUser();
  const isEnabled = !!user?.loggedIn;

  const { data: organizations, isPending: isOrganizationsPending } =
    useOrganizations({ enabled: isEnabled });

  const { data: currentWorkspace, isPending: isWorkspacePending } =
    useWorkspaceByName({ workspaceName }, { enabled: isEnabled });
  const organization = organizations?.find(
    (org) => org.id === currentWorkspace?.organizationId,
  );

  // Published by the organization payload only when the workspace exists and cost intelligence is
  // on -- the same condition under which it can be opened -- so no list has to be scanned for it.
  const spendWorkspaceName = organization?.aiSpendWorkspaceName;

  return {
    isPending:
      isEnabled &&
      (isOrganizationsPending || isWorkspacePending || !workspaceVersion),
    organization,
    spendWorkspaceName,
    isSpendWorkspaceActive: isAiSpendWorkspace(currentWorkspace ?? undefined),
    isEnterprise: organization?.paymentPlan === ORGANIZATION_PLAN_ENTERPRISE,
    isOrganizationAdmin: organization?.role === ORGANIZATION_ROLE_TYPE.admin,
    hasAccess:
      Boolean(spendWorkspaceName) &&
      Boolean(organization?.costIntelligenceEnabled) &&
      workspaceVersion === "v2",
  };
};

export default useAiSpendManager;
