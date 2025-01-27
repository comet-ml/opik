import React from "react";
import ApiKeyInput from "@/components/shared/ApiKeyInput/ApiKeyInput";

export type ApiKeyCardCoreProps = {
  apiKey: string;
};
const ApiKeyCardCore: React.FC<ApiKeyCardCoreProps> = ({ apiKey }) => {
  return (
    <div className="flex flex-1 flex-col justify-between gap-4 rounded-md border bg-white p-6">
      <div className="comet-title-xs text-foreground-secondary">API key</div>
      <ApiKeyInput apiKey={apiKey} />
    </div>
  );
};

export default ApiKeyCardCore;
