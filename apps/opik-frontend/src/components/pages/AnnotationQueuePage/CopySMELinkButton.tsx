import React, { useCallback } from "react";
import copy from "clipboard-copy";
import { AnnotationQueue } from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { Copy } from "lucide-react";
import { generateSMEURL } from "@/lib/annotation-queues";
import useAppStore from "@/store/AppStore";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

interface CopySMELinkButtonProps {
  annotationQueue: AnnotationQueue;
}

const CopySMELinkButton: React.FC<CopySMELinkButtonProps> = ({
  annotationQueue,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const handleCopySMELink = useCallback(() => {
    copy(generateSMEURL(workspaceName, annotationQueue.id));
    toast({
      title: "Annotation queue link copied to clipboard",
      description:
        "Share this queue with your annotators so they can start annotating and provide feedback to improve the evaluation of your LLM application.",
    });
  }, [annotationQueue.id, toast, workspaceName]);

  return (
    <TooltipWrapper content="Share annotation queue link">
      <Button size="sm" variant="outline" onClick={handleCopySMELink}>
        <Copy className="mr-1.5 size-3.5" />
        Share
      </Button>
    </TooltipWrapper>
  );
};

export default CopySMELinkButton;
