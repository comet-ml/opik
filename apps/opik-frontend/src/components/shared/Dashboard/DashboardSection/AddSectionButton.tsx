import React from "react";
import { Plus } from "lucide-react";

interface AddSectionButtonProps {
  onAddSection: () => void;
}

const AddSectionButton: React.FunctionComponent<AddSectionButtonProps> = ({
  onAddSection,
}) => {
  return (
    <div className="flex h-14 w-full items-center border-t">
      <button
        onClick={() => onAddSection()}
        className="flex h-8 w-full items-center justify-center gap-1.5 rounded-md px-3 py-1.5 hover:bg-muted"
      >
        <Plus className="size-3 text-foreground" />
        <span className="text-sm text-foreground">Add section</span>
      </button>
    </div>
  );
};

export default AddSectionButton;
