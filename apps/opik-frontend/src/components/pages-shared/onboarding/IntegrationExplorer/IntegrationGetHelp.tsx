import React from "react";
import { Button } from "@/components/ui/button";
import { HelpCircle } from "lucide-react";
import HelpGuideDialog from "@/components/pages-shared/onboarding/IntegrationExplorer/components/HelpGuideDialog";
import { useIntegrationExplorer } from "@/components/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";

type IntegrationGetHelpProps = {
  className?: string;
  label?: string;
};

const IntegrationGetHelp: React.FunctionComponent<IntegrationGetHelpProps> = ({
  className,
  label = "Get help",
}) => {
  const { helpGuideDialogOpen, setHelpGuideDialogOpen } =
    useIntegrationExplorer();

  const handleOpenChange = (newOpen: boolean) => {
    setHelpGuideDialogOpen(newOpen ? true : undefined);
  };

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => handleOpenChange(true)}
        className={className}
        id="integration-get-help-button"
        data-fs-element="IntegrationGetHelpButton"
      >
        <HelpCircle className="mr-1.5 size-3.5" />
        {label}
      </Button>

      <HelpGuideDialog
        open={!!helpGuideDialogOpen}
        setOpen={handleOpenChange}
      />
    </>
  );
};

export default IntegrationGetHelp;
