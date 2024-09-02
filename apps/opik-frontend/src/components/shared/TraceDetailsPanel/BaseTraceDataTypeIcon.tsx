import React from "react";
import { BASE_TRACE_DATA_TYPE, SPAN_TYPE } from "@/types/traces";
import { TRACE_TYPE_FOR_TREE } from "@/constants/traces";
import { Hammer, InspectionPanel, Link, MessageCircle } from "lucide-react";
import { cn } from "@/lib/utils";

const ICONS_MAP = {
  [TRACE_TYPE_FOR_TREE]: {
    icon: InspectionPanel,
    bg: "#EFE2FD",
    color: "#491B7E",
  },
  [SPAN_TYPE.llm]: {
    icon: MessageCircle,
    bg: "#E2EFFD",
    color: "#19426B",
  },
  [SPAN_TYPE.general]: {
    icon: Link,
    bg: "#DAFBF0",
    color: "#295747",
  },
  [SPAN_TYPE.tool]: {
    icon: Hammer,
    bg: "#FDE2F6",
    color: "#72275F",
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
        "flex size-[22px] items-center justify-center rounded-md flex-shrink-0",
      )}
    >
      <data.icon className="size-3.5" />
    </div>
  );
};

export default BaseTraceDataTypeIcon;
