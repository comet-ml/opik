import React from "react";
import { Info } from "lucide-react";

import { Alert, AlertDescription } from "@/ui/alert";

type RuleEditorNoticeProps = {
  children: React.ReactNode;
};

const RuleEditorNotice: React.FC<RuleEditorNoticeProps> = ({ children }) => (
  <Alert variant="callout" size="sm" className="mb-1">
    <Info />
    <AlertDescription size="sm" className="flex flex-col gap-2">
      {children}
    </AlertDescription>
  </Alert>
);

export default RuleEditorNotice;
