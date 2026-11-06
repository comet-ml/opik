import React from "react";
import { Building2 } from "lucide-react";
import { Button } from "@/ui/button";
import { Tag } from "@/ui/tag";
import { getTimeFromNow } from "@/lib/date";
import { HistoryVersion } from "@/lib/model-prices";

type CostCenterHeaderProps = {
  version: number;
  organizationName?: string;
  canEdit: boolean;
  isAccessPending: boolean;
  latest?: HistoryVersion;
  history: HistoryVersion[];
  isHistoryOpen: boolean;
  onToggleHistory: () => void;
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
    return "One list for every workspace on this installation.";
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
  history,
  isHistoryOpen,
  onToggleHistory,
}) => (
  <div className="mb-4 flex flex-col gap-3">
    <div className="flex items-start gap-3 rounded-md border bg-primary-foreground p-4">
      <div className="flex size-8 shrink-0 items-center justify-center rounded-md bg-primary-100 text-primary">
        <Building2 className="size-4" />
      </div>
      <div className="flex min-w-0 flex-col gap-1">
        <h2 className="comet-body-accented">
          {organizationName
            ? `${organizationName} · organization price list`
            : "Organization price list"}
        </h2>
        <p className="comet-body-s text-muted-slate">
          {describeAccess(organizationName, canEdit, isAccessPending)} Prices
          are in USD per 1M tokens and apply to spans saved after a change. A
          cost sent by the SDK always wins.
        </p>
        <div className="comet-body-s flex flex-wrap items-center gap-x-2 gap-y-1 text-muted-slate">
          {!canEdit && !isAccessPending && (
            <Tag variant="yellow" size="md">
              View only · ask an organization admin
            </Tag>
          )}
          <span>
            {version > 0 ? `Version ${version}` : "No prices saved yet"}
            {latest &&
              ` · saved by ${latest.createdBy} · ${getTimeFromNow(
                latest.createdAt,
              )}`}
          </span>
          {history.length > 0 && (
            <Button variant="link" size="3xs" onClick={onToggleHistory}>
              {isHistoryOpen ? "Hide history" : "History"}
            </Button>
          )}
        </div>
      </div>
    </div>

    {isHistoryOpen && history.length > 0 && (
      <div className="rounded-md border p-4" data-testid="model-price-history">
        <h3 className="comet-body-accented mb-2">History</h3>
        <ul className="flex flex-col gap-2">
          {history.map((entry) => (
            <li key={entry.version} className="comet-body-s">
              <span className="comet-body-s-accented">
                Version {entry.version}
              </span>
              <span className="text-muted-slate">
                {" "}
                · {entry.createdBy} · {getTimeFromNow(entry.createdAt)}
              </span>
              <ul className="list-disc pl-5 text-muted-slate">
                {entry.lines.map((line, index) => (
                  <li key={index}>{line}</li>
                ))}
              </ul>
            </li>
          ))}
        </ul>
        <p className="comet-body-xs mt-3 text-light-slate">
          Every save keeps each row before and after, so any change can be
          traced.
        </p>
      </div>
    )}
  </div>
);

export default CostCenterHeader;
