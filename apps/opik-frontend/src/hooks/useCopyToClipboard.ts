import { useCallback, useEffect, useState } from "react";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";

interface UseCopyToClipboardOptions {
  successMessage?: string;
  errorMessage?: string;
  successIconTimeout?: number;
}

interface UseCopyToClipboardReturn {
  copyToClipboard: (text: string) => Promise<void>;
  showSuccessIcon: boolean;
  isCopying: boolean;
}

export const useCopyToClipboard = (
  options: UseCopyToClipboardOptions = {},
): UseCopyToClipboardReturn => {
  const {
    successMessage = "Copied",
    errorMessage = "Failed to copy to clipboard",
    successIconTimeout = 3000,
  } = options;

  const { toast } = useToast();
  const [showSuccessIcon, setShowSuccessIcon] = useState(false);
  const [isCopying, setIsCopying] = useState(false);

  useEffect(() => {
    let timer: NodeJS.Timeout;
    if (showSuccessIcon) {
      timer = setTimeout(() => setShowSuccessIcon(false), successIconTimeout);
    }
    return () => {
      clearTimeout(timer);
    };
  }, [showSuccessIcon, successIconTimeout]);

  const copyToClipboard = useCallback(
    async (text: string) => {
      try {
        setIsCopying(true);
        await copy(text);
        toast({
          description: successMessage,
        });
        setShowSuccessIcon(true);
      } catch (error) {
        console.error("Failed to copy to clipboard:", error);
        toast({
          description: errorMessage,
          variant: "destructive",
        });
      } finally {
        setIsCopying(false);
      }
    },
    [successMessage, errorMessage, toast],
  );

  return {
    copyToClipboard,
    showSuccessIcon,
    isCopying,
  };
};
