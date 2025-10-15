import React, { useCallback, useEffect, useState } from "react";
import get from "lodash/get";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, UseFormReturn } from "react-hook-form";
import { useBlocker, useNavigate } from "@tanstack/react-router";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
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
import ConfirmDialog from "@/components/shared/ConfirmDialog/ConfirmDialog";
import Loader from "@/components/shared/Loader/Loader";

import { Alert } from "@/types/alerts";
import useAlertCreateMutation from "@/api/alerts/useAlertCreateMutation";
import useAlertUpdateMutation from "@/api/alerts/useAlertUpdateMutation";
import useAppStore from "@/store/AppStore";

import { AlertFormType, AlertFormSchema } from "./schema";
import WebhookSettings from "./WebhookSettings";
import EventTriggers from "./EventTriggers";
import TestWebhookSection from "./TestWebhookSection";
import {
  alertTriggersToFormTriggers,
  formTriggersToAlertTriggers,
} from "./helpers";

type AlertFormProps = {
  alert?: Alert;
  projectsIds: string[];
};

const AlertForm: React.FunctionComponent<AlertFormProps> = ({
  alert,
  projectsIds,
}) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const alertCreateMutation = useAlertCreateMutation();
  const alertUpdateMutation = useAlertUpdateMutation();
  const [showNavigationDialog, setShowNavigationDialog] = useState(false);

  const isEdit = Boolean(alert);
  const title = isEdit ? "Edit alert" : "Create a new alert";
  const submitText = isEdit ? "Update alert" : "Create alert";
  const isPending =
    alertCreateMutation.isPending || alertUpdateMutation.isPending;

  const form: UseFormReturn<AlertFormType> = useForm<AlertFormType>({
    resolver: zodResolver(AlertFormSchema),
    defaultValues: {
      name: alert?.name || "",
      enabled: alert?.enabled ?? true,
      url: alert?.webhook?.url || "",
      secretToken: alert?.webhook?.secret_token || "",
      headers: alert?.webhook?.headers
        ? Object.entries(alert.webhook.headers).map(([key, value]) => ({
            key,
            value,
          }))
        : [],
      triggers: alertTriggersToFormTriggers(alert?.triggers ?? [], projectsIds),
    },
  });

  const getAlert = useCallback(() => {
    const formData = form.getValues();

    return {
      name: formData.name.trim(),
      enabled: formData.enabled,
      webhook: {
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
      },
      triggers: formTriggersToAlertTriggers(formData.triggers, projectsIds),
    };
  }, [form, projectsIds]);

  const handleNavigateBack = useCallback(() => {
    navigate({
      to: "/$workspaceName/configuration",
      params: { workspaceName },
      search: { tab: "alerts" },
    });
  }, [navigate, workspaceName]);

  const canLeavePage = form.formState.isSubmitted || !form.formState.isDirty;

  const { proceed, reset, status } = useBlocker({
    condition: !canLeavePage,
  });

  useEffect(() => {
    if (status === "blocked") {
      setShowNavigationDialog(true);
    }
  }, [status]);

  const onSubmit = useCallback(() => {
    const alertData = getAlert();
    if (isEdit && alert) {
      alertUpdateMutation.mutate(
        {
          alert: {
            ...alert,
            ...alertData,
          },
          alertId: alert.id!,
        },
        {
          onSuccess: handleNavigateBack,
        },
      );
    } else {
      alertCreateMutation.mutate(
        {
          alert: {
            ...alertData,
          },
        },
        {
          onSuccess: handleNavigateBack,
        },
      );
    }
  }, [
    getAlert,
    isEdit,
    alert,
    alertUpdateMutation,
    alertCreateMutation,
    handleNavigateBack,
  ]);

  const handleConfirmNavigation = useCallback(() => {
    setShowNavigationDialog(false);

    if (status === "blocked") {
      proceed();
    }
  }, [status, proceed]);

  const handleCancelNavigation = useCallback(() => {
    if (status === "blocked") {
      reset();
    }
    setShowNavigationDialog(false);
  }, [status, reset]);

  const handleDialogOpenChange = useCallback(
    (open: boolean) => {
      if (!open) {
        handleCancelNavigation();
      }
    },
    [handleCancelNavigation],
  );

  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!canLeavePage) {
        event.preventDefault();
        event.returnValue = "";
      }
    };

    window.addEventListener("beforeunload", handleBeforeUnload);

    return () => {
      window.removeEventListener("beforeunload", handleBeforeUnload);
    };
  }, [canLeavePage]);

  return (
    <div className="py-6">
      {isPending && (
        <div className="absolute inset-0 z-10 flex items-center justify-center bg-background/30">
          <Loader
            className="min-h-56"
            message={
              <div className="comet-body-s-accented text-center">
                {isEdit ? "Updating alert..." : "Creating alert..."}
              </div>
            }
          />
        </div>
      )}
      <h1 className="comet-title-l">{title}</h1>

      <div className="relative mt-6 flex flex-col gap-6 lg:flex-row lg:items-start">
        <div className="flex-1 lg:max-w-[720px]">
          <Form {...form}>
            <form
              className="flex flex-col gap-6"
              onSubmit={form.handleSubmit(onSubmit)}
            >
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
                            placeholder="Name"
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
                          className="comet-body-s-accented"
                        >
                          Enable alert
                        </Label>
                        <Description>
                          Enable to send automatic notifications to the
                          specified URL for selected events.
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

              <EventTriggers form={form} projectsIds={projectsIds} />

              <Separator className="lg:hidden" />

              <div className="lg:hidden">
                <TestWebhookSection
                  form={form}
                  getAlert={getAlert}
                  isPending={isPending}
                />
              </div>

              <Separator className="lg:hidden" />

              <div className="flex gap-2 pt-4">
                <Button
                  type="submit"
                  disabled={form.formState.isSubmitting || isPending}
                >
                  {submitText}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={handleNavigateBack}
                  disabled={form.formState.isSubmitting || isPending}
                >
                  Cancel
                </Button>
              </div>
            </form>
          </Form>
        </div>

        <div className="hidden w-full lg:sticky lg:top-6 lg:block lg:w-2/5 lg:min-w-[320px] lg:max-w-[480px]">
          <TestWebhookSection
            form={form}
            getAlert={getAlert}
            isPending={isPending}
          />
        </div>
      </div>

      <ConfirmDialog
        open={showNavigationDialog}
        setOpen={handleDialogOpenChange}
        onConfirm={handleConfirmNavigation}
        onCancel={handleCancelNavigation}
        title="You have unsaved changes"
        description="If you leave now, your changes will be lost. Are you sure you want to continue?"
        confirmText="Leave without saving"
        cancelText="Stay on page"
        confirmButtonVariant="destructive"
      />
    </div>
  );
};

export default AlertForm;
