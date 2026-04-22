import React from "react";
import { Loader2, Check, AlertCircle } from "lucide-react";
import { DashboardSaveStatus } from "@/v2/pages-shared/dashboards/hooks/useDashboardPersistence";

interface DashboardAutoSaveIndicatorProps {
  saveStatus: DashboardSaveStatus;
}

const DashboardAutoSaveIndicator: React.FunctionComponent<
  DashboardAutoSaveIndicatorProps
> = ({ saveStatus }) => {
  if (saveStatus === "idle") return null;

  return (
    <div className="flex items-center gap-1 text-xs text-muted-slate">
      {saveStatus === "saving" && (
        <>
          <Loader2 className="size-3 animate-spin" />
          <span>Saving...</span>
        </>
      )}
      {saveStatus === "saved" && (
        <>
          <Check className="size-3" />
          <span>Saved</span>
        </>
      )}
      {saveStatus === "error" && (
        <>
          <AlertCircle className="size-3 text-destructive" />
          <span className="text-destructive">Save failed</span>
        </>
      )}
    </div>
  );
};

export default DashboardAutoSaveIndicator;
