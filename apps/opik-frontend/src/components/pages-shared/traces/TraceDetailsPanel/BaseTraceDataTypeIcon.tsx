import React from "react";
import { BASE_TRACE_DATA_TYPE, SPAN_TYPE } from "@/types/traces";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import {
  Construction,
  Hammer,
  InspectionPanel,
  Link,
  MessageCircle,
} from "lucide-react";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const ICONS_MAP = {
  [TRACE_TYPE_FOR_TREE]: {
    icon: InspectionPanel,
    bg: "var(--tag-purple-bg)",
    color: "var(--tag-purple-text)",
    tooltip: "Trace",
  },
  [SPAN_TYPE.llm]: {
    icon: MessageCircle,
    bg: "var(--tag-blue-bg)",
    color: "var(--tag-blue-text)",
    tooltip: "LLM span",
  },
  [SPAN_TYPE.general]: {
    icon: Link,
    bg: "var(--tag-green-bg)",
    color: "var(--tag-green-text)",
    tooltip: "General span",
  },
  [SPAN_TYPE.tool]: {
    icon: Hammer,
    bg: "var(--tag-burgundy-bg)",
    color: "var(--tag-burgundy-text)",
    tooltip: "Tool span",
  },
  [SPAN_TYPE.guardrail]: {
    icon: Construction,
    bg: "var(--tag-orange-bg)",
    color: "var(--tag-orange-text)",
    tooltip: "Guardrail span",
  },
};

type BaseTraceDataTypeIconProps = {
  type: BASE_TRACE_DATA_TYPE;
};

const BaseTraceDataTypeIcon: React.FunctionComponent<
  BaseTraceDataTypeIconProps
> = ({ type = TRACE_TYPE_FOR_TREE }) => {
  const data = ICONS_MAP[type];

  return (
    <div
      style={{ background: data.bg, color: data.color }}
      className={cn(
        "relative flex size-5 items-center justify-center rounded-md flex-shrink-0",
      )}
    >
      <TooltipWrapper content={data.tooltip}>
        <data.icon className="size-3" />
      </TooltipWrapper>
    </div>
  );
};

export default BaseTraceDataTypeIcon;
