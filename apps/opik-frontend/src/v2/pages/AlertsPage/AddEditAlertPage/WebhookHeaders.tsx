import React from "react";
import { UseFormReturn } from "react-hook-form";

import KeyValueFieldArray from "@/shared/KeyValueFieldArray/KeyValueFieldArray";
import { AlertFormType } from "./schema";

type WebhookHeadersProps = {
  form: UseFormReturn<AlertFormType>;
};

const WebhookHeaders: React.FC<WebhookHeadersProps> = ({ form }) => {
  return (
    <KeyValueFieldArray<AlertFormType>
      form={form}
      name="headers"
      label="Headers (optional)"
      description="Specify custom HTTP headers to include with each webhook request. Use them for authentication, content type specification, or any other required metadata."
      showColumnHeaders
      addButtonLabel="Add header"
      newItem={() => ({ key: "", value: "" })}
    />
  );
};

export default WebhookHeaders;
