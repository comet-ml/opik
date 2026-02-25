import React, { useCallback, useEffect, useState } from "react";
import { cva, VariantProps } from "class-variance-authority";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Check, Copy } from "lucide-react";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";

const successIconContainerVariants = cva(
  "flex items-center justify-center [&>svg]:shrink-0",
  {
    variants: {
      size: {
        icon: "size-10 [&>svg]:size-4",
        "icon-lg": "size-11 [&>svg]:size-4",
        "icon-sm": "size-8 [&>svg]:size-3.5",
        "icon-xs": "size-7 [&>svg]:size-3.5",
        "icon-2xs": "size-6 [&>svg]:size-3",
        "icon-3xs": "size-4 [&>svg]:size-3",
      },
    },
    defaultVariants: {
      size: "icon-sm",
    },
  },
);

type CopyButtonProps = {
  text: string;
  message?: string;
  tooltipText?: string;
  successIconTimeout?: number;
  variant?: "default" | "outline" | "ghost";
  className?: string;
  id?: string;
  "data-fs-element"?: string;
} & VariantProps<typeof successIconContainerVariants>;

const CopyButton: React.FunctionComponent<CopyButtonProps> = ({
  text,
  message = "Copied",
  tooltipText = "Copy",
  successIconTimeout = 3000,
  variant = "ghost",
  className,
  size = "icon-sm",
  id,
  "data-fs-element": dataFsElement,
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
      <div className={successIconContainerVariants({ size })}>
        <Check />
      </div>
    );
  }

  if (tooltipText) {
    return (
      <TooltipWrapper content={tooltipText}>
        <Button
          size={size}
          variant={variant}
          className={className}
          onClick={copyClickHandler}
          tabIndex={-1}
          id={id}
          data-fs-element={dataFsElement}
        >
          <Copy />
        </Button>
      </TooltipWrapper>
    );
  }
};

export default CopyButton;
