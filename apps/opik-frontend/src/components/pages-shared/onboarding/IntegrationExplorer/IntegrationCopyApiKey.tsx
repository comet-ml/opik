import React from "react";
import ApiKeyCopyButton, {
  ApiKeyCopyButtonProps,
} from "@/components/shared/ApiKeyCopyButton/ApiKeyCopyButton";

type IntegrationCopyApiKeyProps = ApiKeyCopyButtonProps;

const IntegrationCopyApiKey: React.FunctionComponent<
  IntegrationCopyApiKeyProps
> = (props) => {
  return <ApiKeyCopyButton {...props} />;
};

export default IntegrationCopyApiKey;
