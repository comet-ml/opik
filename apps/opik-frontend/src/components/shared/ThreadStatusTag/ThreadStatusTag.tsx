import React from "react";
import { cn } from "@/lib/utils";
import { MessageCircleMore, MessageCircleOff } from "lucide-react";
import { ThreadStatus } from "@/types/thread";

interface ThreadStatusTagProps {
  status: ThreadStatus;
}

const StatusMap = {
  [ThreadStatus.INACTIVE]: {
    Icon: MessageCircleOff,
    text: "Inactive",
    className: "bg-[var(--thread-inactive)]",
  },
  [ThreadStatus.ACTIVE]: {
    Icon: MessageCircleMore,
    text: "Active",
    className: "bg-[var(--thread-active)]",
  },
};

const ThreadStatusTag: React.FC<ThreadStatusTagProps> = ({ status }) => {
  const { Icon, text, className } = StatusMap[status];
  return (
    <div
      className={cn(
        "comet-body-xs comet-body-s-accented inline-flex h-6 items-center rounded-md px-2",
        className,
      )}
    >
      <Icon className="mr-1 size-3" />
      {text}
    </div>
  );
};

export default ThreadStatusTag;
