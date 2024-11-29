import React, { useState } from "react";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import useRequestChartMutation from "@/api/feedback/useRequestChartMutation";

type RequestChartDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const RequestChartDialog: React.FunctionComponent<RequestChartDialogProps> = ({
  open,
  setOpen,
}) => {
  const [feedback, setFeedback] = useState("");

  const requestChartMutation = useRequestChartMutation();

  const isValid = Boolean(feedback.length);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Request a chart</DialogTitle>
        </DialogHeader>

        <div className="comet-body-s text-muted-slate">
          Please let our team know about which additional metrics you would like
          to see in your Metrics tab.
        </div>

        <div className="size-full overflow-y-auto pb-4">
          <Textarea
            id="provdeFeedback"
            value={feedback}
            onChange={(event) => setFeedback(event.target.value)}
          />
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={!isValid}
              onClick={() => {
                requestChartMutation.mutate({
                  feedback,
                });
              }}
            >
              Submit
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RequestChartDialog;
