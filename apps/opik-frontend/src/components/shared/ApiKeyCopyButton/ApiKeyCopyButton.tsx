import React from "react";
import { Button, ButtonProps } from "@/components/ui/button";
import { Copy } from "lucide-react";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";
import { useUserApiKey } from "@/store/AppStore";

export type ApiKeyCopyButtonProps = {
  className?: string;
  label?: string;
} & Pick<ButtonProps, "variant" | "size" | "disabled">;

const ApiKeyCopyButton: React.FunctionComponent<ApiKeyCopyButtonProps> = ({
  className,
  label = "Copy API key",
  variant = "outline",
  size = "sm",
  disabled,
}) => {
  const { toast } = useToast();
  const apiKey = useUserApiKey();

  const handleCopy = () => {
    if (!apiKey) {
      return;
    }
    copy(apiKey);
    toast({ description: "API key copied to clipboard", duration: 2000 });
  };

  if (!apiKey) {
    return null;
  }

  return (
    <Button
      variant={variant}
      size={size}
      onClick={handleCopy}
      className={className}
      disabled={disabled}
      id="copy-api-key-button"
      data-fs-element="CopyApiKeyButton"
    >
      <Copy className="mr-1.5 size-3.5" />
      {label}
    </Button>
  );
};

export default ApiKeyCopyButton;
