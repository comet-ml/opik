import React from "react";
import { ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageHeaderProps } from "./types";
import { CustomAccordionTrigger } from "@/components/ui/accordion";
import { ROLE_CONFIG } from "./constants";

const PrettyLLMMessageHeader: React.FC<PrettyLLMMessageHeaderProps> =
  React.memo(({ role, label, className }) => {
    const roleConfig = ROLE_CONFIG[role];
    const Icon = roleConfig.icon;

    return (
      <CustomAccordionTrigger
        className={cn(
          "flex select-none items-center justify-between gap-1 rounded-sm p-1 px-0 transition-colors hover:bg-primary-foreground [&[data-state=open]>div>svg:first-child]:rotate-90",
          className,
        )}
      >
        <div className="flex items-center gap-1">
          <ChevronRight className="size-3.5 text-light-slate transition-transform duration-200" />

          <div
            className={cn(
              "size-5 flex items-center justify-center shrink-0 rounded-sm bg-primary-100",
              roleConfig.iconBgColor,
            )}
          >
            <Icon className={cn("size-3 shrink-0", roleConfig.iconColor)} />
          </div>

          <div className="comet-body-s-accented text-light-slate">
            {roleConfig.label}
          </div>
        </div>
        <div className="comet-body-xs text-light-slate">{label}</div>
      </CustomAccordionTrigger>
    );
  });

PrettyLLMMessageHeader.displayName = "PrettyLLMMessageHeader";

export default PrettyLLMMessageHeader;
