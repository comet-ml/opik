import React, { useEffect, useMemo, useRef, useState } from "react";
import { X } from "lucide-react";

import { useActiveWorkspaceName } from "@/store/AppStore";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import { QUOTA_TYPE } from "@/plugins/comet/types/quotas";
import useWorkspaceQuotas from "@/plugins/comet/api/useWorkspaceQuotas";
import useUser from "@/plugins/comet/useUser";

import { Button } from "@/components/ui/button";
import useOrganizations from "@/plugins/comet/useOrganizations";
import { ORGANIZATION_ROLE_TYPE } from "@/plugins/comet/types";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import { buildUrl } from "@/plugins/comet/utils";
import useOrganizationMembers from "@/plugins/comet/api/useOrganizationMembers";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface RetentionBannerProps {
  onChangeHeight: (height: number) => void;
}

const SHOW_BANNER_MIN_THRESHOLD = 0.8;

const RetentionBanner = ({ onChangeHeight }: RetentionBannerProps) => {
  const { data: user } = useUser();
  const heightRef = useRef(0);

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

  const { ref } = useObserveResizeNode<HTMLDivElement>((node) => {
    heightRef.current = node.clientHeight;
    onChangeHeight(node.clientHeight);
  });

  const spanQuota = quotas?.find((q) => q.type === QUOTA_TYPE.OPIK_SPAN_COUNT);

  const isUsedLess80 =
    (spanQuota?.used || 0) / (spanQuota?.limit || 1) <
    SHOW_BANNER_MIN_THRESHOLD;

  const hideBanner = !spanQuota || isUsedLess80 || closed || !user;

  useEffect(() => {
    onChangeHeight(!hideBanner ? heightRef.current : 0);
  }, [hideBanner, onChangeHeight]);

  if (hideBanner) {
    return null;
  }

  const closeBanner = () => {
    setClosed(true);
  };

  const isExceededLimit = spanQuota?.used >= spanQuota?.limit;

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
    <div
      className="z-10 box-border flex min-h-12 items-center justify-center bg-primary p-1.5 text-white transition-all"
      ref={ref}
    >
      <div className="comet-body-s flex flex-1 items-center justify-center text-center">
        {label}
      </div>
      {closable && (
        <Button
          variant="ghost"
          size="icon"
          className="justify-self-end hover:text-white"
          onClick={closeBanner}
        >
          <X />
        </Button>
      )}
    </div>
  );
};

export default RetentionBanner;
