import React from "react";
import { MessageCircleWarning } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ValidationError } from "../SMEFlowContext";

interface ValidationAlertProps {
  errors: ValidationError[];
}

const ValidationAlert: React.FunctionComponent<ValidationAlertProps> = ({
  errors,
}) => {
  if (errors.length === 0) return null;

  return (
    <div className="space-y-2">
      {errors.map((error) => (
        <Alert key={error.type} variant="destructive">
          <MessageCircleWarning />
          <AlertDescription>{error.message}</AlertDescription>
        </Alert>
      ))}
    </div>
  );
};

export default ValidationAlert;
