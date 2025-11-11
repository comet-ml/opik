import React from "react";
import { useTranslation } from "react-i18next";
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
import { buildDocsUrl, cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Description } from "@/components/ui/description";
import { Button } from "@/components/ui/button";

type CustomProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
  isEdit?: boolean;
};

const CustomProviderDetails: React.FC<CustomProviderDetailsProps> = ({
  form,
  isEdit = false,
}) => {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col gap-4 pb-4">
      {!isEdit && (
        <FormField
          control={form.control}
          name="providerName"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["providerName"]);

            return (
              <FormItem>
                <Label htmlFor="providerName">{t("configuration.aiProviders.dialog.providerName")}</Label>
                <FormControl>
                  <Input
                    id="providerName"
                    placeholder={t("configuration.aiProviders.dialog.providerNamePlaceholder")}
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
                  {t("configuration.aiProviders.dialog.providerNameDescription")}
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
              <Label htmlFor="url">{t("configuration.aiProviders.dialog.url")}</Label>
              <FormControl>
                <Input
                  id="url"
                  placeholder={t("configuration.aiProviders.dialog.urlPlaceholder")}
                  value={field.value}
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

      <FormField
        control={form.control}
        name="apiKey"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["apiKey"]);

          return (
            <FormItem>
              <Label htmlFor="apiKey">{t("configuration.aiProviders.dialog.apiKey")}</Label>
              <FormControl>
                <EyeInput
                  id="apiKey"
                  placeholder={t("configuration.aiProviders.dialog.apiKeyPlaceholder")}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {t("configuration.aiProviders.dialog.apiKeyDescription")}{" "}
                <Button
                  variant="link"
                  size="sm"
                  asChild
                  className="inline px-0"
                >
                  <a
                    href={buildDocsUrl("/playground")}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {t("configuration.aiProviders.dialog.documentation")}
                  </a>
                </Button>
                .
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
              <Label htmlFor="models">{t("configuration.aiProviders.dialog.modelsList")}</Label>
              <FormControl>
                <Input
                  id="models"
                  placeholder={t("configuration.aiProviders.dialog.modelsListPlaceholder")}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {t("configuration.aiProviders.dialog.modelsListDescription")}{" "}
                {t("configuration.aiProviders.dialog.modelsListExample")}
              </Description>
            </FormItem>
          );
        }}
      />
    </div>
  );
};

export default CustomProviderDetails;
