import React, { useMemo } from "react";
import { ArrowLeft } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { Button } from "@/components/ui/button";
import useAppStore from "@/store/AppStore";
import { useSMEFlow } from "./SMEFlowContext";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";

const ReturnToAnnotationQueueButton: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { annotationQueue, hasAnyUnsavedChanges } = useSMEFlow();

  const queueId = annotationQueue?.id || "";

  const navigationBlockerConfig = useMemo(
    () => ({
      condition: hasAnyUnsavedChanges,
      title: "Unsaved changes",
      description:
        "You have unsaved changes to your annotations. If you leave now, your changes will be lost.",
      confirmText: "Leave without saving",
      cancelText: "Stay on page",
    }),
    [hasAnyUnsavedChanges],
  );

  const { DialogComponent } = useNavigationBlocker(navigationBlockerConfig);

  return (
    <>
      <Link
        to="/$workspaceName/annotation-queues/$annotationQueueId"
        params={{ workspaceName, annotationQueueId: queueId }}
      >
        <Button variant="ghost" aria-label="Return to annotation queue">
          <ArrowLeft className="mr-2 size-4" />
          Return to annotation queue
        </Button>
      </Link>

      {DialogComponent}
    </>
  );
};

export default ReturnToAnnotationQueueButton;
