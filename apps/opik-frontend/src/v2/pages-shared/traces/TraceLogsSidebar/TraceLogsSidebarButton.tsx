import React, { useCallback } from "react";
import { ListTree } from "lucide-react";
import {
  BooleanParam,
  JsonParam,
  StringParam,
  useQueryParam,
} from "use-query-params";

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
  variant?: "tag" | "icon";
  title?: string;
  backLabel?: string;
};

const TraceLogsSidebarButton: React.FunctionComponent<
  TraceLogsSidebarButtonProps
> = ({
  projectId,
  logsSource,
  sourceFilters,
  variant = "tag",
  title,
  backLabel,
}) => {
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
  const [, setTlsTrace] = useQueryParam(
    `${TLS_QUERY_PREFIX}trace`,
    StringParam,
    { updateType: "replaceIn" },
  );
  const [, setTlsSpan] = useQueryParam(`${TLS_QUERY_PREFIX}span`, StringParam, {
    updateType: "replaceIn",
  });

  const handleOpen = useCallback(() => {
    if (sourceFilters?.length) {
      setTlsFilters(sourceFilters);
    }
    setOpen(true);
  }, [sourceFilters, setTlsFilters, setOpen]);

  const handleClose = useCallback(() => {
    setOpen(undefined);
    setTlsFilters(undefined);
    setTlsTrace(undefined);
    setTlsSpan(undefined);
  }, [setOpen, setTlsFilters, setTlsTrace, setTlsSpan]);

  const trigger =
    variant === "icon" ? (
      <TooltipWrapper content="Go to logs">
        <Button variant="outline" size="icon-2xs" onClick={handleOpen}>
          <ListTree />
        </Button>
      </TooltipWrapper>
    ) : (
      <TooltipWrapper content="Go to logs">
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
            Go to logs
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
        title={title}
        backLabel={backLabel}
      />
    </>
  );
};

export default TraceLogsSidebarButton;
