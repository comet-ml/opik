import React from "react";
import { UseFormReturn } from "react-hook-form";

import KeyValueFieldArray from "@/shared/KeyValueFieldArray/KeyValueFieldArray";
import { AlertFormType, MAX_CUSTOM_FIELDS } from "./schema";

type WebhookCustomFieldsProps = {
  form: UseFormReturn<AlertFormType>;
};

const WebhookCustomFields: React.FC<WebhookCustomFieldsProps> = ({ form }) => {
  return (
    <KeyValueFieldArray<AlertFormType>
      form={form}
      name="customFields"
      label="Custom fields (optional)"
      description="Add key/value pairs to this alert's EventBridge event, for example impact, urgency or level for your incident workflow."
      showColumnHeaders
      addButtonLabel="Add field"
      maxItems={MAX_CUSTOM_FIELDS}
      newItem={() => ({ key: "", value: "" })}
    />
  );
};

export default WebhookCustomFields;
