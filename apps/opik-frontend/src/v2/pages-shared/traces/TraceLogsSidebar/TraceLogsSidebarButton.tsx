import React, { useCallback } from "react";
import { ListTree } from "lucide-react";
import { BooleanParam, JsonParam, useQueryParam } from "use-query-params";

import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TraceLogsSidebar, { TLS_QUERY_PREFIX } from "./TraceLogsSidebar";
import { LOGS_SOURCE } from "@/types/traces";
import { Filter } from "@/types/filters";

type TraceLogsSidebarButtonProps = {
  projectId: string;
  logsSource?: LOGS_SOURCE;
  sourceFilters?: Filter[];
  variant?: "tag" | "button";
};

const TraceLogsSidebarButton: React.FunctionComponent<
  TraceLogsSidebarButtonProps
> = ({ projectId, logsSource, sourceFilters, variant = "tag" }) => {
  const [open = false, setOpen] = useQueryParam(
    `${TLS_QUERY_PREFIX}open`,
    BooleanParam,
    { updateType: "replaceIn" },
  );
  const [, setTlsFilters] = useQueryParam(
    `${TLS_QUERY_PREFIX}filters`,
    JsonParam,
    { updateType: "replaceIn" },
  );

  const handleOpen = useCallback(() => {
    if (sourceFilters?.length) {
      setTlsFilters(sourceFilters);
    }
    setOpen(true);
  }, [sourceFilters, setTlsFilters, setOpen]);

  const handleClose = useCallback(() => {
    setOpen(undefined);
    setTlsFilters(undefined);
  }, [setOpen, setTlsFilters]);

  const trigger =
    variant === "button" ? (
      <Button variant="outline" size="2xs" onClick={handleOpen}>
        <ListTree className="mr-1.5 size-3" />
        Go to traces
      </Button>
    ) : (
      <TooltipWrapper content="View traces">
        <Tag
          size="md"
          variant="transparent"
          className="flex shrink-0 cursor-pointer items-center gap-1 hover:bg-primary-foreground hover:text-foreground active:bg-primary-100 active:text-foreground"
          onClick={handleOpen}
        >
          <ListTree
            className="size-3 shrink-0"
            style={{ color: "var(--color-green)" }}
          />
          <div className="comet-body-s-accented truncate text-muted-slate">
            Go to traces
          </div>
        </Tag>
      </TooltipWrapper>
    );

  return (
    <>
      {trigger}
      <TraceLogsSidebar
        open={Boolean(open)}
        onClose={handleClose}
        projectId={projectId}
        logsSource={logsSource}
      />
    </>
  );
};

export default TraceLogsSidebarButton;
