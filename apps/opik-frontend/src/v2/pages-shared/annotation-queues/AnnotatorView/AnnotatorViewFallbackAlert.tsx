import React from "react";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/ui/alert";

type AnnotatorViewFallbackAlertProps = {
  className?: string;
};

const AnnotatorViewFallbackAlert: React.FC<AnnotatorViewFallbackAlertProps> = ({
  className,
}) => (
  <Alert variant="destructive" size="sm" className={className}>
    <AlertCircle />
    <AlertDescription>
      This queue&apos;s custom view failed to load, so the default view is
      shown.
    </AlertDescription>
  </Alert>
);

export default AnnotatorViewFallbackAlert;
