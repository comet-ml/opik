import React from "react";
import { ArrowLeft } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";

const ReturnToAnnotationQueueButton: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <Link to="/$workspaceName/annotation-queues" params={{ workspaceName }}>
      <Button variant="ghost" aria-label="Return to annotation queue">
        <ArrowLeft className="mr-2 size-4" />
        Return to annotation queue
      </Button>
    </Link>
  );
};

export default ReturnToAnnotationQueueButton;
