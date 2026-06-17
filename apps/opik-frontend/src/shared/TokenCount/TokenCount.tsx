import React from "react";
import { Hash } from "lucide-react";
import { cn, formatNumberInK } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface TokenCountProps {
  tokens: number;
  showLabel?: boolean;
  className?: string;
  iconClassName?: string;
}

const TokenCount: React.FC<TokenCountProps> = ({
  tokens,
  showLabel,
  className,
  iconClassName,
}) => (
  <TooltipWrapper content={`${tokens.toLocaleString()} tokens`}>
    <span className={cn("flex items-center gap-1", className)}>
      <Hash className={cn("size-3", iconClassName)} />
      {formatNumberInK(tokens)}
      {showLabel ? " tokens" : ""}
    </span>
  </TooltipWrapper>
);

export default TokenCount;
