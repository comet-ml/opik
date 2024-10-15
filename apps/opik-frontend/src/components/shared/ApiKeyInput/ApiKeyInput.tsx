import React from "react";
import { KeyRound } from "lucide-react";
import { Input } from "@/components/ui/input";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

type ApiKeyInputProps = {
  apiKey: string;
};

const ApiKeyInput: React.FunctionComponent<ApiKeyInputProps> = ({ apiKey }) => {
  return (
    <div className="flex flex-row items-center">
      <KeyRound className="mr-3 size-6" />
      <Input
        className="mr-2"
        readOnly
        value={`${apiKey.substring(0, 6)}*******************`}
      />
      <CopyButton
        message="Successfully copied API key"
        text={apiKey}
        tooltipText="Copy API key"
      />
    </div>
  );
};

export default ApiKeyInput;
