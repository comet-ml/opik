import React, { useCallback, useState } from "react";

import useAlertCreateMutation from "@/api/alerts/useAlertCreateMutation";
import useAlertUpdateMutation from "@/api/alerts/useAlertUpdateMutation";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Switch } from "@/components/ui/switch";
import { Alert, ALERT_EVENT_TYPE } from "@/types/alerts";

type AddEditAlertDialogProps = {
  alert?: Alert;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddEditAlertDialog: React.FunctionComponent<AddEditAlertDialogProps> = ({
  open,
  setOpen,
  alert,
}) => {
  const alertCreateMutation = useAlertCreateMutation();
  const alertUpdateMutation = useAlertUpdateMutation();

  const [name, setName] = useState(alert?.name ?? "");
  const [url, setUrl] = useState(alert?.url ?? "");
  const [enabled, setEnabled] = useState(alert?.enabled ?? true);
  const [secretToken, setSecretToken] = useState(alert?.secret_token ?? "");

  const isEdit = Boolean(alert);
  const title = isEdit ? "Edit alert" : "Create a new alert";
  const submitText = isEdit ? "Update alert" : "Create alert";

  const isValid = name.trim().length > 0 && url.trim().length > 0;

  const submitHandler = useCallback(() => {
    if (!isValid) return;

    const alertData = {
      name: name.trim(),
      enabled,
      url: url.trim(),
      secret_token: secretToken || undefined,
      events: [
        {
          event_type: ALERT_EVENT_TYPE.trace_errors, // Default event type for now
        },
      ],
    };

    if (isEdit && alert) {
      alertUpdateMutation.mutate({
        alert: {
          ...alert,
          ...alertData,
        },
        alertId: alert.id!,
      });
    } else {
      alertCreateMutation.mutate({
        alert: alertData,
      });
    }
    setOpen(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name, url, enabled, secretToken, isValid, isEdit, alert]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4 pb-4">
          <div className="flex flex-col gap-2">
            <Label htmlFor="alertName">Name</Label>
            <Input
              id="alertName"
              placeholder="Alert name"
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="alertUrl">Webhook URL</Label>
            <Input
              id="alertUrl"
              placeholder="https://hooks.slack.com/..."
              value={url}
              onChange={(event) => setUrl(event.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="secretToken">Secret Token (Optional)</Label>
            <Input
              id="secretToken"
              placeholder="Secret token for webhook authentication"
              value={secretToken}
              onChange={(event) => setSecretToken(event.target.value)}
            />
          </div>

          <div className="flex items-center space-x-2">
            <Switch
              id="enabled"
              checked={enabled}
              onCheckedChange={setEnabled}
            />
            <Label htmlFor="enabled">Enable alert</Label>
          </div>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" disabled={!isValid} onClick={submitHandler}>
              {submitText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditAlertDialog;
