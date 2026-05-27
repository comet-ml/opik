import React from "react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import CopyButton from "@/shared/CopyButton/CopyButton";

interface TraceIdentifierProps {
  label?: string;
  name?: string;
  id: string;
}

const TraceIdentifier: React.FC<TraceIdentifierProps> = ({
  label,
  name,
  id,
}) => {
  if (!id) return null;

  return (
    <div className="flex min-w-0 items-center gap-1.5">
      {label && (
        <span className="comet-body-xs-accented shrink-0">{label}:</span>
      )}
      {name && <span className="comet-body-xs-accented truncate">{name}</span>}
      <div className="group/id flex min-w-0 items-center gap-1">
        <TooltipWrapper content={id}>
          <span className="comet-body-xs truncate text-muted-slate">{id}</span>
        </TooltipWrapper>
        <CopyButton
          text={id}
          message="ID copied to clipboard"
          tooltipText="Copy ID"
          size="icon-xs"
          className="shrink-0 opacity-0 group-hover/id:opacity-100"
        />
      </div>
    </div>
  );
};

export default TraceIdentifier;
