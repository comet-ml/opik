import React, { useCallback } from "react";
import copy from "clipboard-copy";
import { AnnotationQueue } from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import { useToast } from "@/components/ui/use-toast";
import { Copy } from "lucide-react";
import { generateSMEURL } from "@/lib/annotation-queues";
import useAppStore from "@/store/AppStore";

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
      title: "Copied",
      description: "SME link copied to clipboard",
    });
  }, [annotationQueue.id, toast, workspaceName]);

  return (
    <Button variant="outline" size="sm" onClick={handleCopySMELink}>
      <Copy className="mr-1.5 size-3.5" />
      Copy SME link
    </Button>
  );
};

export default CopySMELinkButton;
