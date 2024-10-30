import React, { useEffect, useState } from "react";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Input } from "@/components/ui/input";
import { Button } from "@/components/ui/button";
import useProvideFeedbackMutation from "@/api/feedback/useProvideFeedbackMutation";

type ProvideFeedbackDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const ProvideFeedbackDialog: React.FunctionComponent<
  ProvideFeedbackDialogProps
> = ({ open, setOpen }) => {
  const [feedback, setFeedback] = useState("");
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");

  const provideFeedbackMutation = useProvideFeedbackMutation();

  const isValid = Boolean(feedback.length);

  useEffect(() => {
    if (!open) {
      setFeedback("");
      setEmail("");
      setName("");
    }
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>Provide feedback</DialogTitle>
        </DialogHeader>

        <div className="size-full overflow-y-auto pb-4">
          <Label htmlFor="provideFeedback text-foreground-secondary">
            Share your thoughts!
          </Label>
          <Textarea
            id="provideFeedback"
            value={feedback}
            onChange={(event) => setFeedback(event.target.value)}
          />
        </div>

        <div className="comet-body-s-accented">
          Would you like to chat with us?
        </div>
        <div className="comet-body-s max-w-[570px] text-muted-slate">
          We are always happy to get feedback from our users during a quick
          call. If you would like to chat with us, please enter your details
          below.
        </div>

        <div className="flex flex-row items-center gap-4 pb-4">
          <div className="w-full">
            <Label htmlFor="name">Your name</Label>
            <Input
              id="name"
              placeholder="Name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="w-full">
            <Label htmlFor="email">Your email</Label>
            <Input
              id="email"
              placeholder="Email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
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
                provideFeedbackMutation.mutate({
                  feedback,
                  email,
                  name,
                });
              }}
            >
              Send feedback
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ProvideFeedbackDialog;
