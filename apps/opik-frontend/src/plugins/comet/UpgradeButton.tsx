import React from "react";
import { useMatches } from "@tanstack/react-router";
import { Zap } from "lucide-react";

import { Button } from "@/ui/button";
import useAppStore from "@/store/AppStore";
import useUser from "@/plugins/comet/useUser";
import useOrganizations from "@/plugins/comet/useOrganizations";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import {
  ORGANIZATION_PLAN_ENTERPRISE,
  ORGANIZATION_ROLE_TYPE,
} from "@/plugins/comet/types";
import { buildUrl, isOnPremise, isProduction } from "@/plugins/comet/utils";

const UpgradeButton: React.FC = () => {
  const matches = useMatches();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const hideUpgradeButton = matches.some(
    (match) => match.staticData?.hideUpgradeButton,
  );

  const { data: user } = useUser();
  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  if (!user?.loggedIn || !organizations || !allWorkspaces) {
    return null;
  }

  const workspace = allWorkspaces.find(
    (ws) => ws.workspaceName === workspaceName,
  );

  const organization = organizations.find(
    (org) => org.id === workspace?.organizationId,
  );

  if (!organization) return null;

  const isOrganizationAdmin =
    organization.role === ORGANIZATION_ROLE_TYPE.admin;
  const isAcademic = organization.academic;
  const isEnterpriseCustomer =
    organization.paymentPlan === ORGANIZATION_PLAN_ENTERPRISE;

  if (
    !isProduction() ||
    isOnPremise() ||
    !isOrganizationAdmin ||
    isAcademic ||
    isEnterpriseCustomer ||
    hideUpgradeButton
  ) {
    return null;
  }

  return (
    <Button variant="link" size="2xs" asChild>
      <a
        href={buildUrl(
          `organizations/${organization.id}/billing`,
          workspaceName,
          "&initialOpenUpgradeCard=true",
        )}
        target="_blank"
        rel="noreferrer"
      >
        <Zap className="mr-1.5 size-3 shrink-0" />
        Upgrade
      </a>
    </Button>
  );
};

export default UpgradeButton;
