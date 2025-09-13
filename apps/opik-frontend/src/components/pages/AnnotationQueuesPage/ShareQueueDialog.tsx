import React, { useState } from "react";
import { Copy, ExternalLink, Share2 } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useToast } from "@/components/ui/use-toast";

import { AnnotationQueue } from "@/types/annotation-queues";
import useAnnotationQueueShareMutation from "@/api/annotation-queues/useAnnotationQueueShareMutation";

type ShareQueueDialogProps = {
  queue: AnnotationQueue;
  open: boolean;
  setOpen: (open: boolean) => void;
};

const ShareQueueDialog: React.FunctionComponent<ShareQueueDialogProps> = ({
  queue,
  open,
  setOpen,
}) => {
  const { toast } = useToast();
  const shareMutation = useAnnotationQueueShareMutation();
  const [shareUrl, setShareUrl] = useState<string | null>(null);

  const generateShareUrl = (shareToken: string) => {
    const baseUrl = window.location.origin;
    return `${baseUrl}/sme/queue/${shareToken}`;
  };

  const handleGenerateShare = () => {
    shareMutation.mutate(
      { id: queue.id },
      {
        onSuccess: (updatedQueue) => {
          if (updatedQueue.share_token) {
            const url = generateShareUrl(updatedQueue.share_token);
            setShareUrl(url);
          }
        },
      }
    );
  };

  const handleCopyToClipboard = async () => {
    if (shareUrl) {
      try {
        await navigator.clipboard.writeText(shareUrl);
        toast({
          title: "Copied to clipboard",
          description: "Share URL has been copied to your clipboard",
        });
      } catch (error) {
        toast({
          title: "Failed to copy",
          description: "Please copy the URL manually",
          variant: "destructive",
        });
      }
    }
  };

  const handleOpenInNewTab = () => {
    if (shareUrl) {
      window.open(shareUrl, "_blank");
    }
  };

  const currentShareUrl = shareUrl || (queue.share_token ? generateShareUrl(queue.share_token) : null);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle className="flex items-center space-x-2">
            <Share2 className="h-5 w-5" />
            <span>Share Annotation Queue</span>
          </DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          <div>
            <h4 className="font-medium mb-2">{queue.name}</h4>
            <p className="text-sm text-muted-foreground">
              Generate a shareable link that allows Subject Matter Experts to review and annotate items in this queue.
            </p>
          </div>

          {!currentShareUrl ? (
            <div className="space-y-3">
              <p className="text-sm text-muted-foreground">
                This queue hasn't been shared yet. Generate a share link to allow SMEs to access it.
              </p>
              <Button 
                onClick={handleGenerateShare}
                disabled={shareMutation.isPending}
                className="w-full"
              >
                <Share2 className="mr-2 h-4 w-4" />
                {shareMutation.isPending ? "Generating..." : "Generate Share Link"}
              </Button>
            </div>
          ) : (
            <div className="space-y-3">
              <div>
                <Label htmlFor="share-url">Share URL</Label>
                <div className="flex space-x-2 mt-1">
                  <Input
                    id="share-url"
                    value={currentShareUrl}
                    readOnly
                    className="font-mono text-xs"
                  />
                  <Button 
                    variant="outline" 
                    size="icon"
                    onClick={handleCopyToClipboard}
                    title="Copy to clipboard"
                  >
                    <Copy className="h-4 w-4" />
                  </Button>
                  <Button 
                    variant="outline" 
                    size="icon"
                    onClick={handleOpenInNewTab}
                    title="Open in new tab"
                  >
                    <ExternalLink className="h-4 w-4" />
                  </Button>
                </div>
              </div>

              <div className="text-xs text-muted-foreground">
                <p>• Share this link with SMEs to allow them to review and annotate items</p>
                <p>• SMEs will see a focused interface without access to your main workspace</p>
                <p>• You can monitor progress from the queue details page</p>
              </div>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default ShareQueueDialog;
