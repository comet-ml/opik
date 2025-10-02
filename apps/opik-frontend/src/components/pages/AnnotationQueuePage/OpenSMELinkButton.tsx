import React from "react";
import { SquareArrowOutUpRight } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { AnnotationQueue } from "@/types/annotation-queues";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

interface OpenSMELinkButtonProps {
  annotationQueue: AnnotationQueue;
}

const OpenSMELinkButton: React.FunctionComponent<OpenSMELinkButtonProps> = ({
  annotationQueue,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <Link
      to="/$workspaceName/sme"
      params={{ workspaceName }}
      search={{
        queueId: annotationQueue.id,
      }}
      target="_blank"
    >
      <Button size="sm">
        <SquareArrowOutUpRight className="mr-1.5 size-3.5" />
        Annotate
      </Button>
    </Link>
  );
};

export default OpenSMELinkButton;
