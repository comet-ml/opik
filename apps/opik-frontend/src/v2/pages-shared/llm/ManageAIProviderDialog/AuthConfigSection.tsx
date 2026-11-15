import React, { useCallback, useState } from "react";
import { UseFormReturn, useFieldArray } from "react-hook-form";
import { v4 as uuidv4 } from "uuid";
import get from "lodash/get";
import { AxiosError } from "axios";
import { Lock, LockOpen, Plus, Trash2 } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";
import { RadioGroup, RadioGroupItem } from "@/ui/radio-group";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { FormFieldCard } from "@/v2/pages-shared/llm/FormFieldCard";
import { useToast } from "@/ui/use-toast";
import EyeInput from "@/shared/EyeInput/EyeInput";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { DropdownOption } from "@/types/shared";
import { AUTH_SECRET_SENTINEL, AuthSendAs } from "@/types/providers";
import useProviderKeysAuthCheckMutation from "@/api/provider-keys/useProviderKeysAuthCheckMutation";
import { AIProviderFormType } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import {
  AUTH_SECRET_KEY_PATTERN,
  AuthMode,
  AuthType,
  SERVICE_ACCOUNT_DEFAULT_TTL_SECONDS,
  SERVICE_ACCOUNT_PASSWORD_KEY,
  SERVICE_ACCOUNT_USERNAME_KEY,
  credentialRowsForType,
  formValuesToAuthConfig,
} from "@/v2/pages-shared/llm/ManageAIProviderDialog/customProviderConfig";

const AUTH_TYPE_OPTIONS: DropdownOption<AuthType>[] = [
  { value: "oauth2", label: "OAuth2 client credentials" },
  { value: "service_account", label: "Service account" },
  { value: "custom", label: "Custom" },
];

const SEND_AS_OPTIONS: DropdownOption<AuthSendAs>[] = [
  { value: "form", label: "Form body" },
  { value: "json", label: "JSON body" },
  { value: "basic", label: "Basic auth + form body" },
];

const CREDENTIALS_EXPLAINERS: Record<AuthType, string> = {
  service_account:
    "Sent to the token URL as an HTTP Basic header, with no body. The service can reply with JSON (see Advanced) or with the bare token. The password is encrypted at rest and can't be read back after saving, only replaced.",
  oauth2:
    "Sent to the token URL per the OAuth2 standard: client_id and client_secret go in an HTTP Basic header, the remaining rows (e.g. scope) as a form body along with grant_type=client_credentials, which is added automatically. Locked values are encrypted at rest and can't be read back after saving, only replaced. Fields named like a secret are locked automatically.",
  custom:
    'Sent to the token URL as configured in "Send as". In Basic auth mode, client_id and client_secret (or username and password) go in an HTTP Basic header and the remaining rows in a form body. Locked values are encrypted at rest and can\'t be read back after saving, only replaced. Fields named like a secret are locked automatically.',
};

const ADVANCED_FIELDS = [
  "authTokenField",
  "authExpiresField",
  "authFallbackTtl",
] as const;

type CredentialValueInputProps = {
  form: UseFormReturn<AIProviderFormType>;
  index: number;
  isSaved: boolean;
  testId: string;
  placeholder?: string;
  ariaLabel?: string;
};

/**
 * A credential's value. Secret values are write-only once saved: they load as the backend's
 * sentinel, render empty, and emptying the input again reverts to the sentinel (= keep stored).
 */
const CredentialValueInput: React.FC<CredentialValueInputProps> = ({
  form,
  index,
  isSaved,
  testId,
  placeholder = "Value",
  ariaLabel,
}) => {
  const isSecret = form.watch(`authCredentials.${index}.secret`);
  const value = form.watch(`authCredentials.${index}.value`);
  const isStoredSecret = isSaved && value === AUTH_SECRET_SENTINEL;
  const hasError = Boolean(
    get(form.formState.errors, ["authCredentials", index, "value", "message"]),
  );

  if (isSecret) {
    return (
      <EyeInput
        data-testid={testId}
        aria-label={ariaLabel}
        revealable={!isStoredSecret}
        placeholder={isStoredSecret ? "stored, write-only" : placeholder}
        value={isStoredSecret ? "" : value}
        className={cn({ "border-destructive": hasError })}
        onChange={(e) =>
          form.setValue(
            `authCredentials.${index}.value`,
            e.target.value === "" && isSaved
              ? AUTH_SECRET_SENTINEL
              : e.target.value,
          )
        }
      />
    );
  }

  return (
    <Input
      data-testid={testId}
      aria-label={ariaLabel}
      placeholder={placeholder}
      value={value}
      className={cn({ "border-destructive": hasError })}
      onChange={(e) =>
        form.setValue(`authCredentials.${index}.value`, e.target.value)
      }
    />
  );
};

type AuthConfigSectionProps = {
  form: UseFormReturn<AIProviderFormType>;
  /** The provider's static-auth fields (API key etc.), rendered only while static mode is selected. */
  staticModeFields: React.ReactNode;
};

/**
 * The Authentication block of the custom/Bedrock provider form: a mode switch between the classic
 * static API key (rendered via {@code staticModeFields}) and a token fetched from an auth service
 * (OPIK-7940, OPIK-8732). The token types are UI presets over the backend's single recipe: OAuth2
 * client credentials, a service account (username/password in an HTTP Basic header), or Custom,
 * which exposes the full recipe. The reply-parsing fields under Advanced are shared by all three.
 * Secret credential values are write-only once saved — they load as the backend's sentinel and
 * their lock cannot be removed, mirroring the API contract.
 */
const AuthConfigSection: React.FC<AuthConfigSectionProps> = ({
  form,
  staticModeFields,
}) => {
  const { toast } = useToast();
  const { mutate: checkAuthConfig, isPending: isChecking } =
    useProviderKeysAuthCheckMutation();
  const [isAdvancedOpen, setIsAdvancedOpen] = useState(false);

  const authMode = form.watch("authMode") ?? "api_key";
  const authType = form.watch("authType") ?? "oauth2";
  const credentials = form.watch("authCredentials") ?? [];
  const headers = form.watch("headers");
  const hasStaticAuthorizationHeader = (headers ?? []).some(
    (header) => header.key.trim().toLowerCase() === "authorization",
  );
  const hasAdvancedErrors = ADVANCED_FIELDS.some((field) =>
    Boolean(get(form.formState.errors, [field])),
  );

  const { fields, append, remove, replace } = useFieldArray({
    control: form.control,
    name: "authCredentials",
  });

  const applyTypeDefaults = useCallback(
    (type: AuthType) => {
      if (
        type === "service_account" &&
        !(form.getValues("authFallbackTtl") ?? "").trim()
      ) {
        form.setValue("authFallbackTtl", SERVICE_ACCOUNT_DEFAULT_TTL_SECONDS);
      }
    },
    [form],
  );

  const handleModeChange = useCallback(
    (value: AuthMode) => {
      form.setValue("authMode", value);
      if (
        value === "token" &&
        (form.getValues("authCredentials") ?? []).length === 0
      ) {
        const type = form.getValues("authType") ?? "oauth2";
        replace(credentialRowsForType(type, type, []));
        applyTypeDefaults(type);
      }
    },
    [form, replace, applyTypeDefaults],
  );

  const handleTypeChange = useCallback(
    (nextType: AuthType) => {
      const previousType = form.getValues("authType") ?? "oauth2";
      if (nextType === previousType) {
        return;
      }
      form.setValue("authType", nextType);
      replace(
        credentialRowsForType(
          nextType,
          previousType,
          form.getValues("authCredentials") ?? [],
        ),
      );
      applyTypeDefaults(nextType);
      form.clearErrors("authCredentials");
    },
    [form, replace, applyTypeDefaults],
  );

  const handleCheckConnection = useCallback(() => {
    const authConfig = formValuesToAuthConfig(
      { ...form.getValues(), authMode: "token" },
      { isEditing: false, hadAuthConfig: false },
    );
    const providerId = form.getValues("id");

    checkAuthConfig(
      {
        // the id lets the backend resolve __SECRET__ sentinels against the stored recipe
        ...(providerId && { provider_id: providerId }),
        auth_config: authConfig as never,
      },
      {
        onSuccess: (result) => {
          toast({
            title: "Connection successful",
            description: `Token received, valid for ${result.lifetime_seconds} seconds.`,
          });
        },
        onError: (error: AxiosError) => {
          const message =
            get(error, ["response", "data", "message"]) ??
            get(error, ["response", "data", "errors", "0"], error.message);
          toast({
            title: "Connection failed",
            description: String(message),
            variant: "destructive",
          });
        },
      },
    );
  }, [form, checkAuthConfig, toast]);

  const handleCredentialKeyChange = useCallback(
    (index: number, key: string) => {
      form.setValue(`authCredentials.${index}.key`, key);
      // auto-lock names that look like secrets, visibly, as the user types
      if (
        AUTH_SECRET_KEY_PATTERN.test(key) &&
        !form.getValues(`authCredentials.${index}.secret`)
      ) {
        form.setValue(`authCredentials.${index}.secret`, true);
      }
    },
    [form],
  );

  const renderServiceAccountFields = () => {
    const usernameIndex = credentials.findIndex(
      (entry) => entry.key === SERVICE_ACCOUNT_USERNAME_KEY,
    );
    const passwordIndex = credentials.findIndex(
      (entry) => entry.key === SERVICE_ACCOUNT_PASSWORD_KEY,
    );

    return (
      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-1">
          <Label>Credentials</Label>
          <ExplainerIcon description={CREDENTIALS_EXPLAINERS.service_account} />
        </div>
        <div className="grid grid-cols-2 gap-2">
          {[
            {
              index: usernameIndex,
              label: "Username",
              testId: "auth-service-account-username",
            },
            {
              index: passwordIndex,
              label: "Password",
              testId: "auth-service-account-password",
            },
          ].map(({ index, label, testId }) => {
            const message = get(form.formState.errors, [
              "authCredentials",
              index,
              "value",
              "message",
            ]);
            return (
              <div key={testId} className="flex flex-col gap-1">
                <CredentialValueInput
                  form={form}
                  index={index}
                  isSaved={credentials[index].saved}
                  testId={testId}
                  placeholder={label}
                  ariaLabel={label}
                />
                {message && <FormMessage>{String(message)}</FormMessage>}
              </div>
            );
          })}
        </div>
      </div>
    );
  };

  const renderCredentialRows = (type: Exclude<AuthType, "service_account">) => (
    <div className="flex flex-col gap-3">
      {type === "custom" && (
        <div className="flex flex-col gap-1">
          <Label htmlFor="authSendAs">Send as</Label>
          <SelectBox
            id="authSendAs"
            testId="auth-send-as"
            value={form.watch("authSendAs") ?? "form"}
            onChange={(value: AuthSendAs) => form.setValue("authSendAs", value)}
            options={SEND_AS_OPTIONS}
          />
        </div>
      )}

      <div className="flex items-center gap-1">
        <Label>Credentials</Label>
        <ExplainerIcon description={CREDENTIALS_EXPLAINERS[type]} />
      </div>

      {fields.map((row, index) => {
        const isSecret = form.watch(`authCredentials.${index}.secret`);
        const isSaved = row.saved;
        const keyErrors = get(form.formState.errors, [
          "authCredentials",
          index,
          "key",
        ]);
        const valueErrors = get(form.formState.errors, [
          "authCredentials",
          index,
          "value",
        ]);

        return (
          <div
            key={row.id}
            data-testid="auth-credential-row"
            className="flex items-start gap-2"
          >
            <div className="flex-1">
              <Input
                data-testid="auth-credential-key"
                placeholder="Field name"
                value={form.watch(`authCredentials.${index}.key`)}
                onChange={(e) =>
                  handleCredentialKeyChange(index, e.target.value)
                }
                className={cn({
                  "border-destructive": Boolean(keyErrors?.message),
                })}
              />
              {keyErrors?.message && (
                <FormMessage>{String(keyErrors.message)}</FormMessage>
              )}
            </div>
            <div className="flex-1">
              <CredentialValueInput
                form={form}
                index={index}
                isSaved={isSaved}
                testId="auth-credential-value"
              />
              {valueErrors?.message && (
                <FormMessage>{String(valueErrors.message)}</FormMessage>
              )}
            </div>
            <TooltipWrapper
              content={
                isSaved && isSecret
                  ? "Saved secrets stay secret; the lock cannot be removed"
                  : isSecret
                    ? "Stored encrypted, write-only after saving"
                    : "Mark as secret"
              }
            >
              <Button
                type="button"
                data-testid="auth-credential-lock"
                variant="ghost"
                size="icon"
                disabled={isSaved && isSecret}
                onClick={() =>
                  form.setValue(`authCredentials.${index}.secret`, !isSecret)
                }
              >
                {isSecret ? (
                  <Lock className="size-4 text-primary" />
                ) : (
                  <LockOpen className="size-4" />
                )}
              </Button>
            </TooltipWrapper>
            <Button
              type="button"
              variant="ghost"
              size="icon"
              onClick={() => remove(index)}
            >
              <Trash2 className="size-4" />
            </Button>
          </div>
        );
      })}

      <div>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() =>
            append({
              key: "",
              value: "",
              secret: false,
              saved: false,
              id: uuidv4(),
            })
          }
        >
          <Plus className="mr-1.5 size-3.5" /> Add credential
        </Button>
      </div>
    </div>
  );

  const renderAdvancedField = (
    name: (typeof ADVANCED_FIELDS)[number],
    label: string,
    placeholder: string,
    description: string,
  ) => (
    <FormField
      control={form.control}
      name={name}
      render={({ field, formState }) => {
        const validationErrors = get(formState.errors, [name]);
        return (
          <FormItem>
            <div className="flex items-center gap-1">
              <Label htmlFor={name}>{label}</Label>
              <ExplainerIcon description={description} />
            </div>
            <FormControl>
              <Input
                id={name}
                placeholder={placeholder}
                value={field.value ?? ""}
                onChange={(e) => field.onChange(e.target.value)}
                className={cn({
                  "border-destructive": Boolean(validationErrors?.message),
                })}
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        );
      }}
    />
  );

  return (
    <FormFieldCard title="Authentication" bodyClassName="flex flex-col gap-4">
      <RadioGroup
        value={authMode}
        onValueChange={(value) => handleModeChange(value as AuthMode)}
        className="flex gap-6"
      >
        <div className="flex items-center gap-2">
          <RadioGroupItem value="api_key" id="authModeApiKey" />
          <Label htmlFor="authModeApiKey" className="cursor-pointer">
            Static API key
          </Label>
        </div>
        <div className="flex items-center gap-2">
          <RadioGroupItem value="token" id="authModeToken" />
          <Label htmlFor="authModeToken" className="cursor-pointer">
            Fetched token
          </Label>
          <ExplainerIcon description="Opik requests a short-lived token from your auth service and attaches it to every model call, refreshing it automatically before expiry." />
        </div>
      </RadioGroup>

      {authMode === "api_key" && staticModeFields}

      {authMode === "token" && (
        <>
          <div className="flex flex-col gap-1">
            <Label htmlFor="authType">Type</Label>
            <SelectBox
              id="authType"
              testId="auth-type"
              value={authType}
              onChange={handleTypeChange}
              options={AUTH_TYPE_OPTIONS}
            />
          </div>

          <FormField
            control={form.control}
            name="authTokenUrl"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["authTokenUrl"]);
              return (
                <FormItem>
                  <Label htmlFor="authTokenUrl">Token URL</Label>
                  <FormControl>
                    <Input
                      id="authTokenUrl"
                      type="url"
                      inputMode="url"
                      placeholder="https://auth.example.com/token"
                      value={field.value ?? ""}
                      onChange={(e) => field.onChange(e.target.value)}
                      className={cn({
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />

          {authType === "service_account"
            ? renderServiceAccountFields()
            : renderCredentialRows(authType)}

          <Accordion
            type="multiple"
            value={isAdvancedOpen || hasAdvancedErrors ? ["advanced"] : []}
            onValueChange={(value) =>
              setIsAdvancedOpen(value.includes("advanced"))
            }
          >
            <AccordionItem value="advanced">
              <AccordionTrigger>Advanced</AccordionTrigger>
              <AccordionContent className="flex flex-col gap-4">
                {renderAdvancedField(
                  "authTokenField",
                  "Token field in reply",
                  "access_token",
                  "Where the token is in a JSON reply; dot-path for nested fields. A reply that is the bare token itself is used as-is.",
                )}
                {renderAdvancedField(
                  "authExpiresField",
                  "Lifetime field in reply",
                  "expires_in",
                  "Where the token lifetime, in seconds, is in a JSON reply.",
                )}
                {renderAdvancedField(
                  "authFallbackTtl",
                  "Fallback lifetime (seconds)",
                  "e.g. 3600",
                  "Used when the reply doesn't state a lifetime, including bare-token replies. Opik refreshes the token when 25% of its lifetime remains.",
                )}
              </AccordionContent>
            </AccordionItem>
          </Accordion>

          {hasStaticAuthorizationHeader && (
            <Description className="text-warning">
              A custom <code>Authorization</code> header is configured below —
              it is ignored while token auth is on; the fetched token takes
              precedence.
            </Description>
          )}

          <TooltipWrapper content="Runs the token fetch once, server-side, and reports the token lifetime.">
            <Button
              type="button"
              variant="secondary"
              size="sm"
              className="w-full"
              disabled={isChecking}
              onClick={handleCheckConnection}
            >
              {isChecking ? "Testing…" : "Test connection"}
            </Button>
          </TooltipWrapper>
        </>
      )}
    </FormFieldCard>
  );
};

export default AuthConfigSection;
