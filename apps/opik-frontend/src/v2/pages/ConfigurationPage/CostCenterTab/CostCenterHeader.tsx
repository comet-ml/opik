import React from "react";
import { Tag } from "@/ui/tag";
import { getTimeFromNow } from "@/lib/date";
import { HistoryVersion } from "@/lib/model-prices";

type CostCenterHeaderProps = {
  version: number;
  organizationName?: string;
  canEdit: boolean;
  isAccessPending: boolean;
  latest?: HistoryVersion;
  actions?: React.ReactNode;
};

const describeAccess = (
  organizationName: string | undefined,
  canEdit: boolean,
  isAccessPending: boolean,
) => {
  if (isAccessPending) {
    return organizationName
      ? `One list for every workspace in ${organizationName}.`
      : "Checking who can edit these prices.";
  }
  if (!organizationName) {
    return undefined;
  }
  const scope = `One list for every workspace in ${organizationName}.`;
  return canEdit
    ? `${scope} You can edit it because you are an organization admin.`
    : `${scope} Only organization admins can change it.`;
};

const CostCenterHeader: React.FC<CostCenterHeaderProps> = ({
  version,
  organizationName,
  canEdit,
  isAccessPending,
  latest,
  actions,
}) => {
  const access = describeAccess(organizationName, canEdit, isAccessPending);
  return (
    <div className="mb-4 flex flex-col gap-1">
      <div className="flex items-center justify-between">
        <h2 className="comet-title-xs">Cost center</h2>
        {actions}
      </div>
      <div className="comet-body-s flex flex-wrap items-center gap-x-2 gap-y-1 text-muted-slate">
        {!canEdit && !isAccessPending && (
          <Tag variant="yellow" size="md">
            View only · ask an organization admin
          </Tag>
        )}
        <span>
          {version > 0
            ? `Organization prices: version ${version}`
            : "No organization prices saved yet"}
          {latest &&
            ` · saved by ${latest.createdBy} · ${getTimeFromNow(
              latest.createdAt,
            )}`}
        </span>
        {access && <span>· {access}</span>}
      </div>
    </div>
  );
};

export default CostCenterHeader;
