import React, { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";

type CopyButtonProps = {
  text: string;
  message?: string;
  tooltipText?: string;
  successIconTimeout?: number;
  variant?: "default" | "outline" | "ghost";
  className?: string;
};

const CopyButton: React.FunctionComponent<CopyButtonProps> = ({
  text,
  message = "Copied",
  tooltipText = "Copy",
  successIconTimeout = 3000,
  variant = "ghost",
  className,
}) => {
  const { toast } = useToast();
  const [showSuccessIcon, setShowSuccessIcon] = useState(false);

  useEffect(() => {
    let timer: NodeJS.Timeout;
    if (showSuccessIcon) {
      timer = setTimeout(() => setShowSuccessIcon(false), successIconTimeout);
    }
    return () => {
      clearTimeout(timer);
    };
  }, [showSuccessIcon, successIconTimeout]);

  const copyClickHandler = useCallback(() => {
    toast({
      description: message,
    });
    copy(text);
    setShowSuccessIcon(true);
  }, [message, text, toast]);

  if (showSuccessIcon) {
    return (
      <div className="flex size-8 items-center justify-center">
        <Check className="size-4" />
      </div>
    );
  }

  if (tooltipText) {
    return (
      <TooltipWrapper content={tooltipText}>
        <Button
          size="icon-sm"
          variant={variant}
          className={className}
          onClick={copyClickHandler}
        >
          <Copy />
        </Button>
      </TooltipWrapper>
    );
  }
};

export default CopyButton;
