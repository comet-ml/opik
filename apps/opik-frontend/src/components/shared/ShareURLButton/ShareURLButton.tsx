import React, { useCallback } from "react";
import { Button } from "@/components/ui/button";
import { Share } from "lucide-react";
import copy from "clipboard-copy";
import { useToast } from "@/components/ui/use-toast";

type ShareURLButtonProps = {
  message?: string;
};

const ShareURLButton: React.FunctionComponent<ShareURLButtonProps> = ({
  message = "URL successfully copied to clipboard",
}) => {
  const { toast } = useToast();

  const shareClickHandler = useCallback(() => {
    toast({
      description: message,
    });
    copy(window.location.href);
  }, [message, toast]);

  return (
    <Button variant="outline" size="sm" onClick={shareClickHandler}>
      <Share className="mr-2 size-4" />
      Share
    </Button>
  );
};

export default ShareURLButton;
