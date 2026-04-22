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
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const ICONS_MAP = {
  [TRACE_TYPE_FOR_TREE]: {
    icon: InspectionPanel,
    color: "var(--type-trace)",
    tooltip: "Trace",
  },
  [SPAN_TYPE.llm]: {
    icon: MessageCircle,
    color: "var(--type-span-llm)",
    tooltip: "LLM span",
  },
  [SPAN_TYPE.general]: {
    icon: Link,
    color: "var(--type-span-general)",
    tooltip: "General span",
  },
  [SPAN_TYPE.tool]: {
    icon: Hammer,
    color: "var(--type-span-tool)",
    tooltip: "Tool span",
  },
  [SPAN_TYPE.guardrail]: {
    icon: Construction,
    color: "var(--type-span-guardrail)",
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

  if (!data) return null;

  return (
    <div
      style={{ background: data.color }}
      className="relative flex size-4 shrink-0 items-center justify-center rounded"
    >
      <TooltipWrapper content={data.tooltip}>
        <data.icon className="size-2 text-white" />
      </TooltipWrapper>
    </div>
  );
};

export default BaseTraceDataTypeIcon;
