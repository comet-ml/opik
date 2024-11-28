import React, { useRef, useState } from "react";
import { Split } from "lucide-react";

import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import CompareExperimentsDialog from "@/components/pages/CompareExperimentsPage/CompareExperimentsDialog";

const CompareExperimentsActionsPanel = () => {
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  return (
    <div className="flex items-center gap-2">
      <CompareExperimentsDialog
        key={resetKeyRef.current}
        open={open}
        setOpen={setOpen}
      />
      <TooltipWrapper content="Compare experiments">
        <Button
          variant="outline"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
        >
          <Split className="mr-2 size-4" />
          Compare
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default CompareExperimentsActionsPanel;
