import React from "react";
import { Clock, User } from "lucide-react";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { formatDate, getTimeFromNow } from "@/lib/date";

type VersionMetaProps = {
  createdAt?: string;
  createdBy?: string;
  className?: string;
};

/**
 * Author + relative time row shared by the version-history timeline and the
 * compare-dialog column header. Always renders the same icon set, font, and
 * tooltip formatting so the two surfaces stay visually aligned.
 */
const VersionMeta: React.FC<VersionMetaProps> = ({
  createdAt,
  createdBy,
  className,
}) => {
  if (!createdAt && !createdBy) return null;
  return (
    <div
      className={cn(
        "comet-body-xs flex items-center gap-3 text-light-slate",
        className,
      )}
    >
      {createdAt && (
        <TooltipWrapper
          content={`${formatDate(createdAt, {
            utc: true,
            includeSeconds: true,
          })} UTC`}
        >
          <span className="flex items-center gap-1">
            <Clock className="size-3 shrink-0" />
            {getTimeFromNow(createdAt)}
          </span>
        </TooltipWrapper>
      )}
      {createdBy && (
        <span className="flex items-center gap-1">
          <User className="size-3 shrink-0" />
          {createdBy}
        </span>
      )}
    </div>
  );
};

export default VersionMeta;
