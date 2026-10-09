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
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { useConfirmAction } from "@/shared/ConfirmDialog/useConfirmAction";

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

  // Closing with unsaved edits (Esc, close arrow, outside click, Cancel) asks first.
  const {
    isOpen: isConfirmOpen,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();
  const close = () => setOpen(false);
  const requestClose = () => (isDirty ? requestConfirm(close) : close());
  const handleOpenChange = (next: boolean) => {
    if (next) setOpen(true);
    else if (!isConfirmOpen) requestClose();
  };
  // An outside click would also dismiss the confirm it opens, so open it after
  // the click is handled.
  const handlePointerDownOutside = (event: Event) => {
    if (!isDirty && !isConfirmOpen) return;
    event.preventDefault();
    if (!isConfirmOpen) window.setTimeout(requestClose);
  };

  const save = (andRun: boolean) =>
    mutate(
      { projectId, guidance },
      {
        onSuccess: () => {
          setOpen(false);
          if (andRun) onRun?.();
        },
      },
    );

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col gap-0 p-0 sm:max-w-[560px]"
        onPointerDownOutside={handlePointerDownOutside}
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
            onClick={requestClose}
            disabled={isPending}
          >
            Cancel
          </Button>
          <ButtonWithDropdown>
            <ButtonWithDropdownTrigger
              size="sm"
              triggerClassName="w-8 border-l border-[var(--click-blue)] px-0 disabled:border-muted-disabled [&>svg]:size-3.5"
              disabled={!isDirty || isPending}
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
      <ConfirmDialog
        open={isConfirmOpen}
        setOpen={cancel}
        onConfirm={cancel}
        onCancel={confirm}
        title="Discard changes?"
        description="You have unsaved changes. Do you want to discard them and close?"
        confirmText="Keep editing"
        cancelText="Discard changes"
      />
    </Sheet>
  );
};

export default GuidanceSheet;
