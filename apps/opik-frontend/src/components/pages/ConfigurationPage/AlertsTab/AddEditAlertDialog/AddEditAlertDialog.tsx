import React, { useCallback } from "react";
import get from "lodash/get";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, UseFormReturn } from "react-hook-form";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Label } from "@/components/ui/label";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Separator } from "@/components/ui/separator";
import { Description } from "@/components/ui/description";

import { Alert } from "@/types/alerts";
import useAlertCreateMutation from "@/api/alerts/useAlertCreateMutation";
import useAlertUpdateMutation from "@/api/alerts/useAlertUpdateMutation";

import { AlertFormType, AlertFormSchema } from "./schema";
import WebhookSettings from "./WebhookSettings";
import EventTriggers from "./EventTriggers";
import {
  alertEventsToEventTriggersObject,
  eventTriggersObjectToAlertEvents,
} from "./helpers";

type AddEditAlertDialogProps = {
  alert?: Alert;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const AddEditAlertDialog: React.FunctionComponent<AddEditAlertDialogProps> = ({
  alert,
  open,
  setOpen,
}) => {
  const alertCreateMutation = useAlertCreateMutation();
  const alertUpdateMutation = useAlertUpdateMutation();

  const isEdit = Boolean(alert);
  const title = isEdit ? "Edit alert" : "Create a new alert";
  const submitText = isEdit ? "Update alert" : "Create alert";

  const form: UseFormReturn<AlertFormType> = useForm<AlertFormType>({
    resolver: zodResolver(AlertFormSchema),
    defaultValues: {
      name: alert?.name || "",
      enabled: alert?.enabled ?? true,
      url: alert?.url || "",
      secretToken: alert?.secret_token || "",
      headers: alert?.headers
        ? Object.entries(alert.headers).map(([key, value]) => ({ key, value }))
        : [],
      eventTriggers: alertEventsToEventTriggersObject(alert?.events),
    },
  });

  const onSubmit = useCallback(() => {
    const formData = form.getValues();

    const alertData = {
      name: formData.name.trim(),
      enabled: formData.enabled,
      url: formData.url.trim(),
      secret_token: formData.secretToken || undefined,
      headers:
        formData.headers.length > 0
          ? formData.headers.reduce(
              (acc, header) => ({
                ...acc,
                [header.key]: header.value,
              }),
              {} as Record<string, string>,
            )
          : undefined,
      events: eventTriggersObjectToAlertEvents(formData.eventTriggers),
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
        alert: {
          ...alertData,
        },
      });
    }
    setOpen(false);
  }, [form, isEdit, alert, alertCreateMutation, alertUpdateMutation, setOpen]);

  const handleTestWebhook = useCallback(() => {
    // TODO [ANDRII] - Implement webhook testing functionality
    console.log("Test webhook functionality not implemented yet");
  }, []);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-screen-sm">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <Form {...form}>
            <form
              className="flex flex-col gap-6 pb-4"
              onSubmit={form.handleSubmit(onSubmit)}
            >
              {/* Basic Information */}
              <div className="flex flex-col gap-4">
                <FormField
                  control={form.control}
                  name="name"
                  render={({ field, formState }) => {
                    const validationErrors = get(formState.errors, ["name"]);
                    return (
                      <FormItem>
                        <Label>Name</Label>
                        <FormControl>
                          <Input
                            className={cn({
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                            placeholder="Alert name"
                            {...field}
                          />
                        </FormControl>
                        <FormMessage />
                      </FormItem>
                    );
                  }}
                />

                <FormField
                  control={form.control}
                  name="enabled"
                  render={({ field }) => (
                    <FormItem className="flex flex-row items-center justify-between space-y-0">
                      <div className="flex flex-col">
                        <Label
                          htmlFor="enabled"
                          className="text-sm font-medium"
                        >
                          Enable alert
                        </Label>
                        <Description>
                          Toggle to enable or disable this alert
                        </Description>
                      </div>
                      <FormControl>
                        <Switch
                          id="enabled"
                          checked={field.value}
                          onCheckedChange={field.onChange}
                        />
                      </FormControl>
                    </FormItem>
                  )}
                />
              </div>
              <Separator />
              <WebhookSettings form={form} />
              <Separator />
              <EventTriggers form={form} />
            </form>
          </Form>
        </DialogAutoScrollBody>
        <DialogFooter className="gap-2">
          <Button
            type="button"
            variant="secondary"
            onClick={handleTestWebhook}
            disabled={form.formState.isSubmitting}
          >
            Test webhook
          </Button>
          <div className="flex-auto"></div>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            onClick={form.handleSubmit(onSubmit)}
            disabled={form.formState.isSubmitting}
          >
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditAlertDialog;
