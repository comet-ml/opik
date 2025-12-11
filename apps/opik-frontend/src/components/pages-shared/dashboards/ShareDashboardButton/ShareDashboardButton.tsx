import React, { useCallback } from "react";
import copy from "clipboard-copy";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { Copy } from "lucide-react";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const ShareDashboardButton: React.FC = () => {
  const { toast } = useToast();

  const handleCopyLink = useCallback(() => {
    copy(window.location.href);
    toast({
      title: "Dashboard link copied to clipboard",
      description:
        "Share this link with others to give them access to this dashboard view.",
    });
  }, [toast]);

  return (
    <TooltipWrapper content="Share dashboard link">
      <Button size="sm" variant="outline" onClick={handleCopyLink}>
        <Copy className="mr-1.5 size-3.5" />
        Share
      </Button>
    </TooltipWrapper>
  );
};

export default ShareDashboardButton;
