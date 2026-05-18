import React, { useCallback } from "react";
import { Button } from "@/ui/button";
import { Share } from "lucide-react";
import copy from "clipboard-copy";
import { useToast } from "@/ui/use-toast";

type ShareURLButtonProps = {
  message?: string;
  size?: "sm" | "2xs";
};

const ShareURLButton: React.FunctionComponent<ShareURLButtonProps> = ({
  message = "URL successfully copied to clipboard",
  size = "sm",
}) => {
  const { toast } = useToast();

  const shareClickHandler = useCallback(() => {
    toast({
      description: message,
    });
    copy(window.location.href);
  }, [message, toast]);

  return (
    <Button variant="outline" size={size} onClick={shareClickHandler}>
      <Share className={size === "2xs" ? "mr-1 size-3.5" : "mr-2 size-4"} />
      Share
    </Button>
  );
};

export default ShareURLButton;
