import React from "react";
import { UseFormReturn } from "react-hook-form";

import KeyValueFieldArray from "@/shared/KeyValueFieldArray/KeyValueFieldArray";
import { AlertFormType } from "./schema";

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
      newItem={() => ({ key: "", value: "" })}
    />
  );
};

export default WebhookCustomFields;
