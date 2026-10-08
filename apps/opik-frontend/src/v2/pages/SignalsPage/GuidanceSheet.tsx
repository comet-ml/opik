import React, { useEffect, useRef, useState } from "react";
import { ArrowUpRight } from "lucide-react";
import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import {
  ButtonWithDropdown,
  ButtonWithDropdownContent,
  ButtonWithDropdownItem,
  ButtonWithDropdownTrigger,
} from "@/ui/button-with-dropdown";
import { AgentInsightsJob } from "@/types/signals";
import { formatDate } from "@/lib/date";
import { DIAGNOSTICS_DOCS_URL } from "@/v2/pages/SignalsPage/DiagnosticsEmptyState";
import useUpdateAgentInsightsGuidanceMutation from "@/api/signals/useUpdateAgentInsightsGuidanceMutation";

export const GUIDANCE_MAX_LENGTH = 5000;

type GuidanceSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  job?: AgentInsightsJob | null;
  // Undefined disables "Save and run diagnostic" (run in progress, out of credits).
  onRun?: () => void;
};

const GuidanceSheet: React.FC<GuidanceSheetProps> = ({
  open,
  setOpen,
  projectId,
  job,
  onRun,
}) => {
  const saved = job?.guidance ?? "";
  const [guidance, setGuidance] = useState(saved);
  const { mutate, isPending } = useUpdateAgentInsightsGuidanceMutation();

  // Start from the saved text when the sheet opens, not on every refetch while
  // it is open (that would wipe what's being typed).
  const wasOpen = useRef(false);
  useEffect(() => {
    if (open && !wasOpen.current) setGuidance(saved);
    wasOpen.current = open;
  }, [open, saved]);

  const isDirty = guidance !== saved;

  // Unsaved edits only go away through Cancel; Esc and the close arrow keep them.
  const handleOpenChange = (next: boolean) => {
    if (!next && isDirty) return;
    setOpen(next);
  };

  const save = (andRun: boolean) => {
    const done = () => {
      setOpen(false);
      if (andRun) onRun?.();
    };
    if (!isDirty) {
      done();
      return;
    }
    mutate({ projectId, guidance }, { onSuccess: done });
  };

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col gap-0 p-0 sm:max-w-[560px]"
        header={
          <SheetTopBar
            variant="info"
            title={
              <span className="comet-title-xs text-base leading-5">
                Diagnostics guidance
              </span>
            }
          >
            <Button variant="outline" size="2xs" asChild>
              <a
                href={DIAGNOSTICS_DOCS_URL}
                target="_blank"
                rel="noopener noreferrer"
              >
                Docs
                <ArrowUpRight className="ml-1 size-3" />
              </a>
            </Button>
          </SheetTopBar>
        }
        blockOverlayClose={isDirty}
      >
        <div className="flex min-h-0 flex-1 flex-col gap-1 overflow-y-auto px-5 pb-3 pt-5">
          <Label
            htmlFor="diagnosticsGuidance"
            className="comet-body-s-accented px-0.5 pb-0.5"
          >
            About this project
          </Label>
          <Textarea
            id="diagnosticsGuidance"
            className="comet-body-s h-[330px] min-h-0 resize-none"
            value={guidance}
            maxLength={GUIDANCE_MAX_LENGTH}
            onChange={(e) => setGuidance(e.target.value)}
          />
          <p className="comet-body-s pl-0.5 pr-1 text-light-slate">
            Describe your project, its expected behavior, and any constraints
            Diagnostics should consider. Applies to everyone on this project in
            future diagnostic runs.
          </p>
        </div>

        <div className="flex items-center gap-2 border-t px-5 py-3">
          <span className="comet-body-xs min-w-0 flex-1 truncate pl-0.5 text-light-slate">
            {job?.guidance_updated_at &&
              `Saved ${formatDate(job.guidance_updated_at, {
                format: "MMM D",
              })}${
                job.guidance_updated_by ? ` by ${job.guidance_updated_by}` : ""
              }`}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setOpen(false)}
            disabled={isPending}
          >
            Cancel
          </Button>
          <ButtonWithDropdown>
            <ButtonWithDropdownTrigger
              size="sm"
              triggerClassName="w-8 border-l border-[var(--click-blue)] px-0 [&>svg]:size-3.5"
              disabled={isPending}
              onPrimaryClick={() => save(false)}
            >
              Save guidance
            </ButtonWithDropdownTrigger>
            <ButtonWithDropdownContent align="end" className="w-[227px] p-1.5">
              <ButtonWithDropdownItem
                className="h-6 p-1"
                disabled={!onRun}
                onSelect={() => save(true)}
              >
                Save and run diagnostic
              </ButtonWithDropdownItem>
            </ButtonWithDropdownContent>
          </ButtonWithDropdown>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default GuidanceSheet;
