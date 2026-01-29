import React, { useEffect, useState } from "react";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/components/ui/label";
import EyeInput from "@/components/shared/EyeInput/EyeInput";
import { AIProviderFormType } from "@/components/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import {
  FormControl,
  FormField,
  FormItem,
  FormMessage,
} from "@/components/ui/form";
import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";
import { CheckCircle2, XCircle, Loader2 } from "lucide-react";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";
import useOllamaTestConnectionMutation from "@/api/provider-keys/useOllamaTestConnectionMutation";
import useOllamaListModelsMutation from "@/api/provider-keys/useOllamaListModelsMutation";
import { useToast } from "@/components/ui/use-toast";
import CustomHeadersField from "./CustomHeadersField";

type OllamaProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
  isEdit?: boolean;
};

const OllamaProviderDetails: React.FC<OllamaProviderDetailsProps> = ({
  form,
  isEdit = false,
}) => {
  const { toast } = useToast();
  const [connectionTested, setConnectionTested] = useState(false);
  const [connectionSuccess, setConnectionSuccess] = useState(false);

  const testConnectionMutation = useOllamaTestConnectionMutation();
  const listModelsMutation = useOllamaListModelsMutation();

  const url = form.watch("url");

  // Reset connection status when URL changes
  useEffect(() => {
    setConnectionTested(false);
    setConnectionSuccess(false);
  }, [url]);

  const handleTestConnection = async () => {
    if (!url) {
      toast({
        title: "URL required",
        description: "Please enter the Ollama base URL first",
        variant: "destructive",
      });
      return;
    }

    try {
      const response = await testConnectionMutation.mutateAsync({
        base_url: url,
      });

      setConnectionTested(true);
      setConnectionSuccess(response.connected);

      if (response.connected) {
        toast({
          title: "Connection successful",
          description: `Connected to Ollama ${response.version || ""}`,
        });

        // Auto-fetch models if connection successful
        handleFetchModels();
      } else {
        toast({
          title: "Connection failed",
          description: response.error_message || "Unable to connect to Ollama",
          variant: "destructive",
        });
      }
    } catch (error) {
      setConnectionTested(true);
      setConnectionSuccess(false);
      toast({
        title: "Connection failed",
        description: "Unable to connect to Ollama instance",
        variant: "destructive",
      });
    }
  };

  const handleFetchModels = async () => {
    if (!url) {
      return;
    }

    try {
      const models = await listModelsMutation.mutateAsync({
        base_url: url,
      });

      if (models.length > 0) {
        const modelNames = models.map((m) => m.name).join(", ");
        form.setValue("models", modelNames);

        toast({
          title: "Models discovered",
          description: `Found ${models.length} model(s)`,
        });
      } else {
        toast({
          title: "No models found",
          description: "No models are available on this Ollama instance",
          variant: "default",
        });
      }
    } catch (error) {
      toast({
        title: "Failed to fetch models",
        description: "Unable to retrieve models from Ollama",
        variant: "destructive",
      });
    }
  };

  const getDefaultUrl = () => {
    return PROVIDERS[PROVIDER_TYPE.OLLAMA].defaultUrl;
  };

  return (
    <div className="flex flex-col gap-4 pb-4">
      <p className="comet-body-s text-muted-slate">
        {PROVIDERS[PROVIDER_TYPE.OLLAMA].description}
      </p>
      {!isEdit && (
        <FormField
          control={form.control}
          name="providerName"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["providerName"]);

            return (
              <FormItem>
                <Label htmlFor="providerName">Provider name</Label>
                <FormControl>
                  <Input
                    id="providerName"
                    placeholder="my-ollama-instance"
                    value={field.value}
                    onChange={(e) => field.onChange(e.target.value)}
                    disabled={isEdit}
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
                <Description>
                  A unique name for this Ollama instance (e.g.,
                  &ldquo;local&rdquo;, &ldquo;cloud&rdquo;,
                  &ldquo;production&rdquo;).
                </Description>
              </FormItem>
            );
          }}
        />
      )}
      <FormField
        control={form.control}
        name="url"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["url"]);

          return (
            <FormItem>
              <div className="flex items-center justify-between">
                <Label htmlFor="url">Ollama URL</Label>
                <Button
                  type="button"
                  variant="link"
                  size="sm"
                  onClick={() => field.onChange(getDefaultUrl())}
                  className="h-auto p-0 text-xs"
                >
                  Use default URL
                </Button>
              </div>
              <FormControl>
                <Input
                  id="url"
                  placeholder={getDefaultUrl()}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                <strong>Important:</strong> URL must end with{" "}
                <code className="text-xs">/v1</code> for OpenAI API
                compatibility. For local Ollama:{" "}
                <code className="text-xs">http://localhost:11434/v1</code>. For
                remote:{" "}
                <code className="text-xs">http://ollama-test:11434/v1</code> or
                your server URL with <code className="text-xs">/v1</code>{" "}
                suffix.
              </Description>
            </FormItem>
          );
        }}
      />

      <div className="flex gap-2">
        <Button
          type="button"
          variant="outline"
          onClick={handleTestConnection}
          disabled={!url || testConnectionMutation.isPending}
          className="flex-1"
        >
          {testConnectionMutation.isPending ? (
            <>
              <Loader2 className="mr-2 size-4 animate-spin" />
              Testing...
            </>
          ) : connectionTested ? (
            connectionSuccess ? (
              <>
                <CheckCircle2 className="mr-2 size-4 text-green-600" />
                Connected
              </>
            ) : (
              <>
                <XCircle className="mr-2 size-4 text-destructive" />
                Failed
              </>
            )
          ) : (
            "Test Connection"
          )}
        </Button>

        <Button
          type="button"
          variant="outline"
          onClick={handleFetchModels}
          disabled={!url || listModelsMutation.isPending}
          className="flex-1"
        >
          {listModelsMutation.isPending ? (
            <>
              <Loader2 className="mr-2 size-4 animate-spin" />
              Fetching...
            </>
          ) : (
            "Discover Models"
          )}
        </Button>
      </div>

      <FormField
        control={form.control}
        name="apiKey"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["apiKey"]);

          return (
            <FormItem>
              <Label htmlFor="apiKey">API key (optional)</Label>
              <FormControl>
                <EyeInput
                  id="apiKey"
                  placeholder="API key"
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                Most Ollama instances don&apos;t require an API key. Only add
                one if your instance is configured with authentication.
              </Description>
            </FormItem>
          );
        }}
      />

      <FormField
        control={form.control}
        name="models"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["models"]);

          return (
            <FormItem>
              <Label htmlFor="models">Models list</Label>
              <FormControl>
                <Input
                  id="models"
                  placeholder="llama3, mistral, codellama"
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                Comma-separated list of available models. Use &ldquo;Discover
                Models&rdquo; to auto-populate this field.
              </Description>
            </FormItem>
          );
        }}
      />

      <CustomHeadersField
        form={form}
        description="Optional custom headers for authentication or configuration."
      />
    </div>
  );
};

export default OllamaProviderDetails;
