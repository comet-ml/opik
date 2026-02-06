import React, { useMemo } from "react";
import { Save, Check, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { useDataView, loadView } from "@/lib/data-view";

// Storage key prefix for custom views
const STORAGE_KEY_PREFIX = "custom-view:";

interface SaveViewButtonProps {
  projectId: string | null | undefined;
  onSave: () => void;
  isSaving: boolean;
  saveVersion: number;
}

const SaveViewButton: React.FC<SaveViewButtonProps> = ({
  projectId,
  onSave,
  isSaving,
  saveVersion,
}) => {
  const { tree } = useDataView();

  // Check if current tree matches saved tree (compare only essential properties, ignoring metadata)
  const isSaved = useMemo(() => {
    if (!tree?.root || !projectId) {
      return false;
    }

    const savedTree = loadView(`${STORAGE_KEY_PREFIX}${projectId}`);
    if (!savedTree) return false;
    // Compare only version, root, and nodes - ignore metadata like timestamps
    return (
      savedTree.version === tree.version &&
      savedTree.root === tree.root &&
      JSON.stringify(savedTree.nodes) === JSON.stringify(tree.nodes)
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tree, projectId, saveVersion]);

  const hasTree = Boolean(tree?.root);
  const isDisabled = !hasTree || !projectId || isSaved;

  const getButtonContent = () => {
    if (isSaving) {
      return (
        <>
          <Loader2 className="mr-2 size-4 animate-spin" />
          Saving...
        </>
      );
    }

    if (isSaved) {
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
    if (!hasTree) {
      return "Generate a view first";
    }
    if (!projectId) {
      return "Select a project first";
    }
    if (isSaved) {
      return "View is already saved";
    }
    return "Save this view for the current project";
  };

  return (
    <TooltipWrapper content={getTooltipContent()}>
      <Button
        variant={isSaved ? "outline" : "default"}
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
