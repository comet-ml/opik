import React, { useEffect, useState } from "react";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import Loader from "@/shared/Loader/Loader";
import { describeReplaceResult, rowLabel } from "@/lib/model-prices";
import { ReviewedSave } from "./useModelPricesEditor";

type ReviewModelPricesDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  preview: () => Promise<ReviewedSave | undefined>;
  save: (reviewed: ReviewedSave) => Promise<ReviewedSave | undefined>;
};

const Section: React.FC<{ title: string; lines: string[] }> = ({
  title,
  lines,
}) =>
  lines.length ? (
    <div className="flex flex-col gap-1">
      <h3 className="comet-body-s-accented">
        {title} ({lines.length})
      </h3>
      <ul className="comet-body-s flex list-disc flex-col gap-0.5 pl-5">
        {lines.map((line, index) => (
          <li key={index}>{line}</li>
        ))}
      </ul>
    </div>
  ) : null;

const ReviewModelPricesDialog: React.FC<ReviewModelPricesDialogProps> = ({
  open,
  setOpen,
  preview,
  save,
}) => {
  const [outcome, setOutcome] = useState<ReviewedSave>();
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    preview().then((checked) => {
      if (cancelled) return;
      if (checked) {
        setOutcome(checked);
      } else {
        setOpen(false);
      }
    });
    return () => {
      cancelled = true;
    };
    // Runs once per opening: the dialog is remounted each time it opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const confirm = async () => {
    if (!outcome) return;
    setIsSaving(true);
    // Save exactly what was reviewed: if the list changed meanwhile, its old version gets a 409 and a fresh review.
    await save(outcome);
    setOpen(false);
  };

  const described = outcome && describeReplaceResult(outcome.result);
  const isEmpty =
    described &&
    !described.created.length &&
    !described.updated.length &&
    !described.deleted.length;
  const warnings = outcome?.result.warnings ?? [];

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>Review price changes</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {!outcome || !described ? (
            <div className="flex flex-col items-center gap-2 py-8">
              <Loader />
              <p className="comet-body-s text-muted-slate">
                Checking the changes…
              </p>
            </div>
          ) : (
            <div className="flex flex-col gap-4 pb-2">
              {isEmpty ? (
                <p className="comet-body-s">
                  Nothing to save: the list already matches these prices.
                </p>
              ) : (
                <>
                  <Section title="Added" lines={described.created} />
                  <Section title="Changed" lines={described.updated} />
                  <Section title="Removed" lines={described.deleted} />
                  {outcome.result.unchanged > 0 && (
                    <p className="comet-body-s text-muted-slate">
                      {outcome.result.unchanged} other{" "}
                      {outcome.result.unchanged === 1
                        ? "row stays"
                        : "rows stay"}{" "}
                      the same.
                    </p>
                  )}
                </>
              )}
              {warnings.length > 0 && (
                <div className="flex flex-col gap-1 rounded-md bg-warning-box-bg p-3 text-warning-box-text">
                  <h3 className="comet-body-s-accented">Check these</h3>
                  <ul className="comet-body-s flex flex-col gap-1">
                    {warnings.map((warning, index) => {
                      const row =
                        warning.row == null
                          ? undefined
                          : outcome.request.rows[warning.row];
                      return (
                        <li key={index}>
                          {row && (
                            <span className="comet-code">
                              {rowLabel(row)}:{" "}
                            </span>
                          )}
                          {warning.message}
                        </li>
                      );
                    })}
                  </ul>
                </div>
              )}
              <p className="comet-body-xs text-light-slate">
                Saving makes a new version of the organization&apos;s list. The
                new prices apply to spans saved after this; spans already saved
                keep their cost.
              </p>
            </div>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button
            onClick={confirm}
            disabled={!outcome || Boolean(isEmpty) || isSaving}
          >
            {isSaving ? "Saving…" : "Save prices"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ReviewModelPricesDialog;
