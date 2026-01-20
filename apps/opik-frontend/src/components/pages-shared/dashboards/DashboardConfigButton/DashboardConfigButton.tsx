import React, { useState } from "react";
import { Settings } from "lucide-react";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import DashboardConfigDialog from "./DashboardConfigDialog";

interface DashboardConfigButtonProps {
  disableProjectSelector?: boolean;
  disableExperimentsSelector?: boolean;
}

const DashboardConfigButton: React.FC<DashboardConfigButtonProps> = ({
  disableProjectSelector = false,
  disableExperimentsSelector = false,
}) => {
  const [open, setOpen] = useState(false);

  return (
    <>
      <TooltipWrapper content="Dashboard defaults">
        <Button size="sm" variant="outline" onClick={() => setOpen(true)}>
          <Settings className="mr-1.5 size-3.5" />
          Defaults
        </Button>
      </TooltipWrapper>
      <DashboardConfigDialog
        open={open}
        onOpenChange={setOpen}
        disableProjectSelector={disableProjectSelector}
        disableExperimentsSelector={disableExperimentsSelector}
      />
    </>
  );
};

export default DashboardConfigButton;
