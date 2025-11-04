import React from "react";
import { HelpCircle } from "lucide-react";
import TruncationConfigPopover from "@/components/shared/TruncationConfigPopover/TruncationConfigPopover";
import { TRUNCATION_DISABLED_MAX_PAGE_SIZE } from "@/constants/shared";

const TruncationDisabledWarning: React.FC = () => {
  return (
    <TruncationConfigPopover
      message={`Pagination limited to ${TRUNCATION_DISABLED_MAX_PAGE_SIZE} items. Enable truncation in preferences to view more items per page.`}
    >
      <div className="flex cursor-help items-center gap-1 text-xs text-muted-foreground">
        <span>Pagination limited</span>
        <HelpCircle className="size-3" />
      </div>
    </TruncationConfigPopover>
  );
};

export default TruncationDisabledWarning;
