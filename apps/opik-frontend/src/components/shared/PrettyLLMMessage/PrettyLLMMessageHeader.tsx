import React from "react";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageHeaderProps } from "./types";
import { CollapsibleSectionHeader } from "@/components/shared/CollapsibleSection";
import { ROLE_CONFIG } from "./constants";

const PrettyLLMMessageHeader: React.FC<PrettyLLMMessageHeaderProps> = ({
  role,
  label,
  className,
}) => {
  const roleConfig = ROLE_CONFIG[role];
  const Icon = roleConfig.icon;

  return (
    <CollapsibleSectionHeader
      className={className}
      leftContent={
        <>
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
        </>
      }
      rightContent={
        <div className="comet-body-xs text-light-slate">{label}</div>
      }
    />
  );
};

export default PrettyLLMMessageHeader;
