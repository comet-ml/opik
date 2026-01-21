import React from "react";
import { Save, Check, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { CustomViewSchema, ViewSource } from "@/types/custom-view";

interface SaveViewButtonProps {
  schema: CustomViewSchema | null;
  projectId: string | null | undefined;
  viewSource: ViewSource;
  onSave: () => void;
  isSaving: boolean;
}

const SaveViewButton: React.FC<SaveViewButtonProps> = ({
  schema,
  projectId,
  viewSource,
  onSave,
  isSaving,
}) => {
  const isDisabled = !schema || !projectId || viewSource === "saved";

  const getButtonContent = () => {
    if (isSaving) {
      return (
        <>
          <Loader2 className="mr-2 size-4 animate-spin" />
          Saving...
        </>
      );
    }

    if (viewSource === "saved") {
      return (
        <>
          <Check className="mr-2 size-4" />
          Saved
        </>
      );
    }

    return (
      <>
        <Save className="mr-2 size-4" />
        Save View
      </>
    );
  };

  const getTooltipContent = () => {
    if (!schema) {
      return "Generate a view first";
    }
    if (!projectId) {
      return "Select a project first";
    }
    if (viewSource === "saved") {
      return "View is already saved";
    }
    return "Save this view for the current project";
  };

  return (
    <TooltipWrapper content={getTooltipContent()}>
      <Button
        variant={viewSource === "saved" ? "outline" : "default"}
        size="sm"
        onClick={onSave}
        disabled={isDisabled || isSaving}
      >
        {getButtonContent()}
      </Button>
    </TooltipWrapper>
  );
};

export default SaveViewButton;
