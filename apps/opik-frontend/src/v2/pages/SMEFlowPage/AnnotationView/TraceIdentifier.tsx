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
    <div className="group mb-3 flex min-w-0 items-center gap-1.5">
      {label && (
        <span className="comet-body-s-accented shrink-0">{label}:</span>
      )}
      {name && <span className="comet-body-s-accented truncate">{name}</span>}
      {name && <span className="text-light-slate">/</span>}
      <TooltipWrapper content={id}>
        <span className="comet-body-s truncate text-light-slate">{id}</span>
      </TooltipWrapper>
      <CopyButton
        text={id}
        message="ID copied to clipboard"
        tooltipText="Copy ID"
        size="icon-xs"
        className="shrink-0 opacity-0 group-hover:opacity-100"
      />
    </div>
  );
};

export default TraceIdentifier;
