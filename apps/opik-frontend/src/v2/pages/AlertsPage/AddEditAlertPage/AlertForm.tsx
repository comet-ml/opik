import React, { useCallback, useEffect, useMemo, useRef } from "react";
import get from "lodash/get";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, UseFormReturn, useWatch } from "react-hook-form";
import { useNavigate } from "@tanstack/react-router";
import { ExternalLink } from "lucide-react";

import { buildFullBaseUrl, cn } from "@/lib/utils";
import { buildDocsUrl } from "@/v2/lib/utils";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { Form, FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { Input } from "@/ui/input";
import { Switch } from "@/ui/switch";
import { Separator } from "@/ui/separator";
import { Description } from "@/ui/description";
import Loader from "@/shared/Loader/Loader";

import { Alert, ALERT_TYPE } from "@/types/alerts";
import useAlertCreateMutation from "@/api/alerts/useAlertCreateMutation";
import useAlertUpdateMutation from "@/api/alerts/useAlertUpdateMutation";
import useProjectAlertsList from "@/api/alerts/useProjectAlertsList";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";

import { AlertFormType, AlertFormSchema } from "./schema";
import EventTriggers from "./EventTriggers";
import WebhookSettings from "./WebhookSettings";
import useWebhookTest from "./useWebhookTest";
import { buildAlertName, ensureUniqueAlertName } from "./alertNameHelpers";
import {
  alertTriggersToFormTriggers,
  formTriggersToAlertTriggers,
} from "./helpers";

type AlertFormProps = {
  alert?: Alert;
};

const AlertForm: React.FunctionComponent<AlertFormProps> = ({ alert }) => {
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const alertCreateMutation = useAlertCreateMutation();
  const alertUpdateMutation = useAlertUpdateMutation();

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
      alertType: alert?.alert_type || ALERT_TYPE.general,
      routingKey: alert?.metadata?.routing_key || "",
      url: alert?.webhook?.url || "",
      secretToken: alert?.webhook?.secret_token || "",
      headers: alert?.webhook?.headers
        ? Object.entries(alert.webhook.headers).map(([key, value]) => ({
            key,
            value,
          }))
        : [],
      triggers: alertTriggersToFormTriggers(alert?.triggers ?? []),
    },
  });

  const getAlert = useCallback(() => {
    const formData = form.watch();

    return {
      name: formData.name.trim(),
      enabled: formData.enabled,
      alert_type: formData.alertType,
      project_id: activeProjectId ?? undefined,
      metadata: {
        ...alert?.metadata,
        base_url: buildFullBaseUrl(),
        ...(formData.alertType === ALERT_TYPE.pagerduty && {
          routing_key: formData.routingKey
            ? formData.routingKey.trim()
            : undefined,
        }),
      },
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
      triggers: formTriggersToAlertTriggers(formData.triggers),
    };
  }, [form, activeProjectId, alert?.metadata]);

  const { testConnection, testTrigger, isTestPending } = useWebhookTest({
    getAlert,
  });

  const triggers = useWatch({ control: form.control, name: "triggers" });
  const nameValue = useWatch({ control: form.control, name: "name" });

  // The last name we suggested. formState.dirtyFields can't stand in for this:
  // once a suggestion is written, the value differs from the "" default, so RHF
  // reports the field as dirty and we could never tell a suggestion apart from
  // something the user typed.
  const suggestedNameRef = useRef("");

  const { data: alertsList } = useProjectAlertsList(
    { projectId: activeProjectId!, page: 1, size: 100 },
    { enabled: !isEdit && Boolean(activeProjectId) },
  );

  const existingAlertNames = useMemo(
    () => (alertsList?.content ?? []).map(({ name }) => name),
    [alertsList],
  );

  // Name new alerts after their triggers until the user types their own.
  // Clearing the field back to empty hands naming back to us.
  useEffect(() => {
    if (isEdit) return;

    const currentName = form.getValues("name");
    if (currentName && currentName !== suggestedNameRef.current) return;

    const generated = buildAlertName(triggers ?? []);
    const nextName = generated
      ? ensureUniqueAlertName(generated, existingAlertNames)
      : "";

    if (nextName !== currentName) {
      suggestedNameRef.current = nextName;
      form.setValue("name", nextName, { shouldDirty: false });
    }
  }, [form, isEdit, triggers, nameValue, existingAlertNames]);

  const handleNavigateBack = useCallback(() => {
    navigate({
      to: "/$workspaceName/projects/$projectId/alerts",
      params: { workspaceName, projectId: activeProjectId! },
    });
  }, [navigate, workspaceName, activeProjectId]);

  const canLeavePage = form.formState.isSubmitted || !form.formState.isDirty;

  const { DialogComponent } = useNavigationBlocker({
    condition: !canLeavePage,
    title: "You have unsaved changes",
    description:
      "If you leave now, your changes will be lost. Are you sure you want to continue?",
    confirmText: "Leave without saving",
    cancelText: "Stay on page",
  });

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
      <div className="flex min-h-7 max-w-[720px] items-center justify-between gap-2">
        <h1 className="comet-title-xs truncate">{title}</h1>
        <Button
          variant="ghost"
          size="2xs"
          className="comet-body-xs shrink-0 text-muted-slate"
          asChild
        >
          <a
            href={buildDocsUrl("/production/alerts/alerts")}
            target="_blank"
            rel="noreferrer"
          >
            Go to docs
            <ExternalLink className="ml-1 size-3.5 shrink-0" />
          </a>
        </Button>
      </div>

      <div className="relative mt-6 max-w-[720px]">
        <Form {...form}>
          <form
            className="flex flex-col gap-6"
            onSubmit={form.handleSubmit(onSubmit)}
          >
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
                        data-testid="alert-name-input"
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

            <Separator />

            <EventTriggers
              form={form}
              projectId={alert?.project_id || activeProjectId!}
              getAlert={getAlert}
              onTestTrigger={testTrigger}
              isTestPending={isTestPending}
              isPending={isPending}
            />

            <WebhookSettings
              form={form}
              onTestConnection={testConnection}
              isTestPending={isTestPending}
              isPending={isPending}
            />

            {isEdit && (
              <>
                <Separator />

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
              </>
            )}

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
      {DialogComponent}
    </div>
  );
};

export default AlertForm;
