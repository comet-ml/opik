import React from "react";
import { MessagesSquare } from "lucide-react";
import BaseTraceDataTypeIcon from "@/shared/BaseTraceDataTypeIcon/BaseTraceDataTypeIcon";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";

type AnnotatorViewSampleLabelProps = {
  scope: "trace" | "thread";
  name: string;
  id: string;
};

const shortenId = (id: string) =>
  id.length <= 12 ? id : `${id.slice(0, 4)}...${id.slice(-4)}`;

const AnnotatorViewSampleLabel: React.FC<AnnotatorViewSampleLabelProps> = ({
  scope,
  name,
  id,
}) => (
  <span className="flex min-w-0 items-center gap-1">
    {scope === "trace" ? (
      <BaseTraceDataTypeIcon type={TRACE_TYPE_FOR_TREE} />
    ) : (
      <span className="flex size-4 shrink-0 items-center justify-center rounded bg-[var(--type-thread)]">
        <MessagesSquare className="size-2 text-white" />
      </span>
    )}
    <span className="comet-body-xs-accented truncate">{name}</span>
    <span className="shrink-0 py-0.5 text-[10px] leading-3 text-muted-slate">
      {shortenId(id)}
    </span>
  </span>
);

export default AnnotatorViewSampleLabel;
