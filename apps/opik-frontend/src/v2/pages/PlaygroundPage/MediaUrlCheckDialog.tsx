import React from "react";

import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { MediaUrlCheckResult } from "@/api/playground/useMediaUrlsCheckMutation";
import { describeMediaUrlFailure } from "@/v2/pages/PlaygroundPage/mediaUrlCheck";

const MAX_LISTED_FAILURES = 5;

interface MediaUrlCheckDialogProps {
  failures: MediaUrlCheckResult[] | null;
  onRunAnyway: () => void;
  onCancel: () => void;
}

const MediaUrlCheckDialog: React.FC<MediaUrlCheckDialogProps> = ({
  failures,
  onRunAnyway,
  onCancel,
}) => {
  const listedFailures = (failures ?? []).slice(0, MAX_LISTED_FAILURES);
  const hiddenCount = (failures?.length ?? 0) - listedFailures.length;

  return (
    <Dialog
      open={!!failures}
      onOpenChange={(open) => {
        if (!open) onCancel();
      }}
    >
      <DialogContent
        className="max-w-lg sm:max-w-[560px]"
        data-testid="media-url-check-dialog"
      >
        <DialogHeader>
          <DialogTitle>Some media URLs didn&apos;t load</DialogTitle>
          <DialogDescription>
            We tried to load the media in this run and these URLs failed. The
            model will probably fail on them too, and the rows that use them
            will show an error.
          </DialogDescription>
        </DialogHeader>
        <DialogAutoScrollBody>
          <ul className="flex flex-col gap-3">
            {listedFailures.map((failure) => (
              <li key={failure.url} className="flex min-w-0 flex-col gap-0.5">
                <span className="comet-code break-all text-foreground">
                  {failure.url}
                </span>
                <span className="comet-body-s text-muted-slate">
                  {describeMediaUrlFailure(failure)}
                </span>
              </li>
            ))}
          </ul>
          {hiddenCount > 0 && (
            <p className="comet-body-s mt-3 text-muted-slate">
              And {hiddenCount} more
            </p>
          )}
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button onClick={onRunAnyway}>Run anyway</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default MediaUrlCheckDialog;
