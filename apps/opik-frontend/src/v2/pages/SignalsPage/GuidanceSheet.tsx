import React, { useEffect, useRef, useState } from "react";
import { ArrowUpRight } from "lucide-react";
import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Label } from "@/ui/label";
import { AutoGrowTextarea } from "@/ui/auto-grow-textarea";
import {
  ButtonWithDropdown,
  ButtonWithDropdownContent,
  ButtonWithDropdownItem,
  ButtonWithDropdownTrigger,
} from "@/ui/button-with-dropdown";
import { AgentInsightsJob } from "@/types/signals";
import { formatDate } from "@/lib/date";
import { DIAGNOSTICS_DOCS_URL } from "@/v2/pages/SignalsPage/helpers";
import useUpdateAgentInsightsGuidanceMutation from "@/api/signals/useUpdateAgentInsightsGuidanceMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { useConfirmAction } from "@/shared/ConfirmDialog/useConfirmAction";

export const GUIDANCE_MAX_LENGTH = 5000;

type GuidanceSheetProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  // Undefined while the job is still loading. Null when the project has no job yet.
  job?: AgentInsightsJob | null;
  // Undefined leaves only Save (run in progress, out of credits, no permission to run).
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
  // Until the job loads, the saved text isn't known, so there's nothing to edit
  // or save (an empty draft saved then would clear the project's guidance).
  const isLoaded = job !== undefined;
  const [guidance, setGuidance] = useState(saved);
  const { mutate, isPending } = useUpdateAgentInsightsGuidanceMutation();

  // Start from the saved text as soon as it's loaded, and follow newer saves
  // (someone else saving) while the draft is untouched; once edited, a refetch
  // never wipes what's being typed. Null = nothing seeded in this opening yet.
  const seededText = useRef<string | null>(null);
  useEffect(() => {
    if (!open) {
      seededText.current = null;
      return;
    }
    if (!isLoaded) return;
    const untouched =
      seededText.current === null || guidance === seededText.current;
    if (untouched && guidance !== saved) setGuidance(saved);
    if (untouched) seededText.current = saved;
  }, [open, isLoaded, saved, guidance]);

  // A run started elsewhere while saving (another tab, auto-run) drops onRun,
  // so "Save and run" checks the latest one, not the one from when it was clicked.
  const onRunRef = useRef(onRun);
  useEffect(() => {
    onRunRef.current = onRun;
  }, [onRun]);

  const isDirty = isLoaded && guidance !== saved;

  // Closing with unsaved edits (Esc, close arrow, outside click, Cancel) asks first.
  const {
    isOpen: isConfirmOpen,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();
  const close = () => setOpen(false);
  const requestClose = () => (isDirty ? requestConfirm(close) : close());
  // While saving, the sheet stays open: the save closes it when it lands.
  const handleOpenChange = (next: boolean) => {
    if (next) setOpen(true);
    else if (!isConfirmOpen && !isPending) requestClose();
  };
  // An outside click would also dismiss the confirm it opens, so open it after
  // the click is handled.
  const handlePointerDownOutside = (event: Event) => {
    if (!isDirty && !isConfirmOpen && !isPending) return;
    event.preventDefault();
    if (!isConfirmOpen && !isPending) window.setTimeout(requestClose);
  };

  // One save at a time: a second one would land after the first and drop its run.
  const save = (andRun: boolean) => {
    if (isPending) return;
    mutate(
      { projectId, guidance },
      {
        onSuccess: () => {
          setOpen(false);
          if (andRun) onRunRef.current?.();
        },
      },
    );
  };

  // Cmd/Ctrl+Enter does what the primary button does, like the other forms with a text area.
  const handleKeyDown = (event: React.KeyboardEvent) => {
    if (event.key !== "Enter" || !(event.metaKey || event.ctrlKey)) return;
    event.preventDefault();
    if (isDirty) save(Boolean(onRun));
  };

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col gap-0 p-0 sm:max-w-[560px]"
        onPointerDownOutside={handlePointerDownOutside}
        onKeyDown={handleKeyDown}
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
          {/* Grows with the text from 330px; the sheet body scrolls, the footer stays put. */}
          <AutoGrowTextarea
            id="diagnosticsGuidance"
            className="comet-body-s min-h-[330px] shrink-0 border-input leading-5"
            value={guidance}
            readOnly={isPending || !isLoaded}
            maxLength={GUIDANCE_MAX_LENGTH}
            onChange={setGuidance}
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
          {onRun ? (
            <ButtonWithDropdown>
              <ButtonWithDropdownTrigger
                size="sm"
                triggerClassName="w-8 border-l border-[var(--click-blue)] px-0 disabled:border-muted-disabled [&>svg]:size-3.5"
                disabled={!isDirty || isPending}
                onPrimaryClick={() => save(true)}
              >
                Save and run diagnostic
              </ButtonWithDropdownTrigger>
              <ButtonWithDropdownContent
                align="end"
                className="w-[227px] p-1.5"
              >
                <ButtonWithDropdownItem
                  className="h-6 p-1"
                  disabled={isPending}
                  onSelect={() => save(false)}
                >
                  Save guidance
                </ButtonWithDropdownItem>
              </ButtonWithDropdownContent>
            </ButtonWithDropdown>
          ) : (
            <Button
              size="sm"
              disabled={!isDirty || isPending}
              onClick={() => save(false)}
            >
              Save guidance
            </Button>
          )}
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
