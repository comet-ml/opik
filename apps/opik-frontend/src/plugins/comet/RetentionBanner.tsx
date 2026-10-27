import React, { useMemo, useState } from "react";

import { useActiveWorkspaceName } from "@/store/AppStore";
import { QUOTA_TYPE } from "@/plugins/comet/types/quotas";
import useWorkspaceQuotas from "@/plugins/comet/api/useWorkspaceQuotas";
import useUser from "@/plugins/comet/useUser";

import useOrganizations from "@/plugins/comet/useOrganizations";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import { buildUrl } from "@/plugins/comet/utils";
import useOrganizationMembers from "@/plugins/comet/api/useOrganizationMembers";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import Banner from "@/v2/layout/Banner/Banner";

interface RetentionBannerProps {
  onChangeHeight: (height: number) => void;
}

const SHOW_BANNER_MIN_THRESHOLD = 0.8;

const RetentionBanner = ({ onChangeHeight }: RetentionBannerProps) => {
  const { data: user } = useUser();

  const [closed, setClosed] = useState(false);
  const activeWorkspaceName = useActiveWorkspaceName();

  const { data: quotas } = useWorkspaceQuotas(
    { workspaceName: activeWorkspaceName },
    { enabled: !!activeWorkspaceName && !!user?.loggedIn },
  );

  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (workspace) => workspace.workspaceName === activeWorkspaceName,
  );

  const { data: organizations } = useOrganizations({
    enabled: !!user?.loggedIn,
  });

  const organization = organizations?.find((org) => {
    return org.id === workspace?.organizationId;
  });

  const { data: organizationMembers } = useOrganizationMembers(
    {
      organizationId: organization?.id ?? "",
    },
    {
      enabled: !!organization?.id,
    },
  );

  const firstThreeAdmins = useMemo(() => {
    return organizationMembers
      ?.filter?.((m) => m.role === ORGANIZATION_ROLE_TYPE.admin)
      .slice(0, 3);
  }, [organizationMembers]);

  const isOrganizationAdmin =
    organization?.role === ORGANIZATION_ROLE_TYPE.admin;

  const spanQuota = quotas?.find((q) => q.type === QUOTA_TYPE.OPIK_SPAN_COUNT);

  const isUsedLess80 =
    (spanQuota?.used || 0) / (spanQuota?.limit || 1) <
    SHOW_BANNER_MIN_THRESHOLD;

  const hideBanner = !spanQuota || isUsedLess80 || closed || !user;

  const isExceededLimit = !!spanQuota && spanQuota.used >= spanQuota.limit;

  const getExceededLabel = () => {
    if (isOrganizationAdmin) {
      return (
        <span>
          You&apos;ve hit your plan limits.{" "}
          <a
            href={buildUrl(
              `organizations/${workspace?.organizationId}/billing`,
            )}
            target="_blank"
            rel="noopener noreferrer"
            className="whitespace-nowrap underline"
          >
            Upgrade your plan
          </a>{" "}
          now to keep monitoring running smoothly.
        </span>
      );
    }

    const isOneAdmin = firstThreeAdmins?.length === 1;

    return (
      <span>
        You&apos;ve hit your plan limits. To keep monitoring running, ask your
        organization {isOneAdmin ? "admin" : "admins"} (
        {firstThreeAdmins?.map((user, idx) => (
          <React.Fragment key={user.userName}>
            <TooltipWrapper content={user.email}>
              <span className="underline">{user.userName}</span>
            </TooltipWrapper>
            {idx !== firstThreeAdmins.length - 1 && ", "}
          </React.Fragment>
        ))}
        {!isOneAdmin && "..."}) to upgrade your plan.
      </span>
    );
  };

  const label = isExceededLimit
    ? getExceededLabel()
    : `You're at 80% of your ${spanQuota?.limit} free spans. Upgrade to ensure` +
      "uninterrupted monitoring";

  const closable = !isExceededLimit;

  return (
    <Banner
      visible={!hideBanner}
      onChangeHeight={onChangeHeight}
      initialHeight={0}
      ariaLabel="Plan limits"
      onDismiss={closable ? () => setClosed(true) : undefined}
      className="min-h-12 p-1.5 transition-all"
      contentClassName="comet-body-s"
    >
      {label}
    </Banner>
  );
};

export default RetentionBanner;
