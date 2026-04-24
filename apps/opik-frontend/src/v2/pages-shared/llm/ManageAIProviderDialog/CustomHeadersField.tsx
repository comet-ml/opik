import React from "react";
import { UseFormReturn } from "react-hook-form";
import { v4 as uuidv4 } from "uuid";

import KeyValueFieldArray from "@/shared/KeyValueFieldArray/KeyValueFieldArray";
import { AIProviderFormType } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";

type KeyValueFieldName = "headers" | "queryParams";

type CustomHeadersFieldProps = {
  form: UseFormReturn<AIProviderFormType>;
  name?: KeyValueFieldName;
  label?: string;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  addButtonLabel?: string;
  description?: string;
};

/// Thin Custom-LLM-provider-specific wrapper over the shared
/// `KeyValueFieldArray`. Keeps the existing call sites (header + query-param
/// editors in `CustomProviderDetails`) stable while routing to the shared
/// component so any future tweaks — styling, accessibility, validation — land
/// in one place for every consumer in the app.
const CustomHeadersField: React.FC<CustomHeadersFieldProps> = ({
  form,
  name = "headers",
  label = "Custom headers (optional)",
  keyPlaceholder = "Header name",
  valuePlaceholder = "Header value",
  addButtonLabel = "Add header",
  description = "Custom providers may require additional headers beyond the API key. Add them here as key-value pairs.",
}) => {
  return (
    <KeyValueFieldArray<AIProviderFormType>
      form={form}
      name={name}
      label={label}
      description={description}
      keyPlaceholder={keyPlaceholder}
      valuePlaceholder={valuePlaceholder}
      addButtonLabel={addButtonLabel}
      // Custom LLM schema requires an `id` string on every row to satisfy
      // the Zod contract shared with the load/save serialization helpers.
      newItem={() => ({ key: "", value: "", id: uuidv4() })}
    />
  );
};

export default CustomHeadersField;
