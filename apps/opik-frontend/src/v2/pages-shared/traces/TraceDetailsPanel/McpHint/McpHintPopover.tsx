import React from "react";
import { ArrowRight, Plug } from "lucide-react";

import { buildDocsUrl } from "@/lib/utils";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import {
  MCP_HINT_DESCRIPTION,
  MCP_HINT_DOCS_PATH,
  MCP_HINT_TITLE,
} from "./constants";

type McpHintPopoverProps = {
  /** Called when the user leaves through the popover rather than abandoning it. */
  onAction: () => void;
};

const McpHintPopover: React.FunctionComponent<McpHintPopoverProps> = ({
  onAction,
}) => {
  const handleLearnMoreClick = () => {
    onAction();
    trackEvent(OpikEvent.MCP_LEARN_MORE_CLICKED);
  };

  return (
    <div className="w-[279px] rounded-md border bg-background p-1 font-mono shadow-lg">
      <div className="flex items-center gap-1.5 px-1.5 pb-1">
        <Plug className="size-4 shrink-0 text-[var(--color-ollie)]" />
        <span className="comet-body-xs leading-4 text-foreground">
          {MCP_HINT_TITLE}
        </span>
      </div>
      <div className="my-1 h-px w-full bg-border" />

      <div className="px-2 pb-1 pt-0.5">
        <p className="comet-body-xs leading-4 text-muted-slate">
          {MCP_HINT_DESCRIPTION}
        </p>

        <a
          href={buildDocsUrl(MCP_HINT_DOCS_PATH)}
          target="_blank"
          rel="noreferrer"
          onClick={handleLearnMoreClick}
          className="comet-body-xs mt-3 inline-flex items-center gap-1 leading-4 text-foreground underline underline-offset-2 hover:text-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <span>Learn more</span>
          <ArrowRight className="size-3 shrink-0" />
        </a>
      </div>
    </div>
  );
};

export default McpHintPopover;
