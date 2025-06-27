import React, { useMemo, useRef, useState } from "react";
import copy from "clipboard-copy";
import { Copy, MoreHorizontal, Share } from "lucide-react";
import DatabasePlus from "@/icons/database-plus.svg?react";

import { Span, Trace } from "@/types/traces";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { useToast } from "@/components/ui/use-toast";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import AddToDatasetDialog from "@/components/pages-shared/traces/AddToDatasetDialog/AddToDatasetDialog";
import {
  DetailsActionSection,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import { ButtonLayoutSize } from "@/components/pages-shared/traces/DetailsActionSection";
import DetailsActionSectionToggle from "../../DetailsActionSection/DetailsActionSectionToggle";

type TraceDataViewerActionsPanelProps = {
  layoutSize: ButtonLayoutSize;
  data: Trace | Span;
  traceId: string;
  spanId?: string;
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue) => void;
};

const TraceDataViewerActionsPanel: React.FunctionComponent<
  TraceDataViewerActionsPanelProps
> = ({
  data,
  traceId,
  spanId,
  activeSection,
  setActiveSection,
  layoutSize,
}) => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const { toast } = useToast();

  const rows = useMemo(() => (data ? [data] : []), [data]);

  const annotationCount = data.feedback_scores?.length;
  const commentsCount = data.comments?.length;

  return (
    <>
      <AddToDatasetDialog
        key={resetKeyRef.current}
        rows={rows}
        open={open}
        setOpen={setOpen}
      />

      <TooltipWrapper content="Add to dataset">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
        >
          <DatabasePlus className="size-3.5" />
          {layoutSize === "lg" && <div className="pl-1">Add to dataset</div>}
        </Button>
      </TooltipWrapper>

      <DetailsActionSectionToggle
        activeSection={activeSection}
        setActiveSection={setActiveSection}
        layoutSize={layoutSize}
        count={commentsCount}
        type={DetailsActionSection.Comments}
      />
      <DetailsActionSectionToggle
        activeSection={activeSection}
        setActiveSection={setActiveSection}
        layoutSize={layoutSize}
        count={annotationCount}
        type={DetailsActionSection.Annotations}
      />

      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-sm">
            <span className="sr-only">Actions menu</span>
            <MoreHorizontal />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              toast({
                description: "URL successfully copied to clipboard",
              });
              copy(window.location.href);
            }}
          >
            <Share className="mr-2 size-4" />
            Share
          </DropdownMenuItem>
          <TooltipWrapper content={traceId} side="left">
            <DropdownMenuItem
              onClick={() => {
                toast({
                  description: `Trace ID successfully copied to clipboard`,
                });
                copy(traceId);
              }}
            >
              <Copy className="mr-2 size-4" />
              Copy trace ID
            </DropdownMenuItem>
          </TooltipWrapper>
          {spanId && (
            <TooltipWrapper content={spanId} side="left">
              <DropdownMenuItem
                onClick={() => {
                  toast({
                    description: `Span ID successfully copied to clipboard`,
                  });
                  copy(spanId);
                }}
              >
                <Copy className="mr-2 size-4" />
                Copy span ID
              </DropdownMenuItem>
            </TooltipWrapper>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
};

export default TraceDataViewerActionsPanel;
