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
    bg: "#EFE2FD",
    color: "#491B7E",
    tooltip: "Trace",
  },
  [SPAN_TYPE.llm]: {
    icon: MessageCircle,
    bg: "#E2EFFD",
    color: "#19426B",
    tooltip: "LLM",
  },
  [SPAN_TYPE.general]: {
    icon: Link,
    bg: "#DAFBF0",
    color: "#295747",
    tooltip: "General",
  },
  [SPAN_TYPE.tool]: {
    icon: Hammer,
    bg: "#FDE2F6",
    color: "#72275F",
    tooltip: "Tool",
  },
  [SPAN_TYPE.guardrail]: {
    icon: Construction,
    bg: "#FEE8D7",
    color: "#734A2B",
    tooltip: "Guardrail",
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
        "relative flex size-[22px] items-center justify-center rounded-md flex-shrink-0",
      )}
    >
      <TooltipWrapper content={data.tooltip}>
        <data.icon className="size-3.5" />
      </TooltipWrapper>
    </div>
  );
};

export default BaseTraceDataTypeIcon;
