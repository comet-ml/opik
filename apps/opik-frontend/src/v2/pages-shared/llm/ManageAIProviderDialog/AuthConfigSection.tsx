import React, { useCallback } from "react";
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
import { FormFieldCard } from "@/v2/pages-shared/llm/FormFieldCard";
import { useToast } from "@/ui/use-toast";
import EyeInput from "@/shared/EyeInput/EyeInput";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { AUTH_SECRET_SENTINEL } from "@/types/providers";
import useProviderKeysAuthCheckMutation from "@/api/provider-keys/useProviderKeysAuthCheckMutation";
import { AIProviderFormType } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import {
  AUTH_SECRET_KEY_PATTERN,
  AuthMode,
  formValuesToAuthConfig,
  oauth2CredentialRows,
} from "@/v2/pages-shared/llm/ManageAIProviderDialog/customProviderConfig";

type AuthConfigSectionProps = {
  form: UseFormReturn<AIProviderFormType>;
  /** The provider's static-auth fields (API key etc.), rendered only while static mode is selected. */
  staticModeFields: React.ReactNode;
};

/**
 * The Authentication block of the custom/Bedrock provider form: a mode switch between the classic
 * static API key (rendered via {@code staticModeFields}) and dynamic token auth (OPIK-7940). Of
 * the backend's general token-auth recipe, the UI surfaces only the OAuth2 client credentials
 * flow — other recipe shapes remain API-only.
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

  const authMode = form.watch("authMode") ?? "api_key";
  const headers = form.watch("headers");
  const hasStaticAuthorizationHeader = (headers ?? []).some(
    (header) => header.key.trim().toLowerCase() === "authorization",
  );

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "authCredentials",
  });

  const handleModeChange = useCallback(
    (value: AuthMode) => {
      form.setValue("authMode", value);
      if (
        value === "token" &&
        (form.getValues("authCredentials") ?? []).length === 0
      ) {
        form.setValue("authCredentials", oauth2CredentialRows());
      }
    },
    [form],
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
            OAuth2 client credentials
          </Label>
          <ExplainerIcon description="Opik requests a short-lived access token from your auth service using the OAuth2 client credentials grant and attaches it to every model call, refreshing it automatically before expiry." />
        </div>
      </RadioGroup>

      {authMode === "api_key" && staticModeFields}

      {authMode === "token" && (
        <>
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
                      placeholder="https://auth.example.com/oauth/token"
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

          <div className="flex flex-col gap-3">
            <div className="flex items-center gap-1">
              <Label>Credentials</Label>
              <ExplainerIcon description="Sent to the token URL per the OAuth2 standard: client_id and client_secret go in an HTTP Basic header, the remaining rows (e.g. scope) as a form body along with grant_type=client_credentials, which is added automatically. Locked values are encrypted at rest and can't be read back after saving, only replaced. Fields named like a secret are locked automatically." />
            </div>

            {fields.map((row, index) => {
              const isSecret = form.watch(`authCredentials.${index}.secret`);
              const isSaved = row.saved;
              const value = form.watch(`authCredentials.${index}.value`);
              const isStoredSecret = isSaved && value === AUTH_SECRET_SENTINEL;
              const keyErrors = get(form.formState.errors, [
                "authCredentials",
                index,
                "key",
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
                    {isSecret ? (
                      <EyeInput
                        data-testid="auth-credential-value"
                        revealable={!isStoredSecret}
                        placeholder={
                          isStoredSecret ? "stored, write-only" : "Value"
                        }
                        // Emptying a saved secret reverts to the sentinel (= keep stored).
                        value={isStoredSecret ? "" : value}
                        onChange={(e) =>
                          form.setValue(
                            `authCredentials.${index}.value`,
                            e.target.value === "" && isSaved
                              ? AUTH_SECRET_SENTINEL
                              : e.target.value,
                          )
                        }
                      />
                    ) : (
                      <Input
                        data-testid="auth-credential-value"
                        placeholder="Value"
                        value={value}
                        onChange={(e) =>
                          form.setValue(
                            `authCredentials.${index}.value`,
                            e.target.value,
                          )
                        }
                      />
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
                        form.setValue(
                          `authCredentials.${index}.secret`,
                          !isSecret,
                        )
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
