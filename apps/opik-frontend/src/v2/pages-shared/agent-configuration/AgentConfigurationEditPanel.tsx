import React, { useRef, useState } from "react";
import { ArrowRight, GitCompare, Pencil, Undo2, X } from "lucide-react";

import { ConfigHistoryItem } from "@/types/agent-configs";
import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTitle } from "@/ui/sheet";
import { Tag } from "@/ui/tag";
import { Textarea } from "@/ui/textarea";
import AgentConfigurationEditView, {
  AgentConfigurationEditViewHandle,
  AgentConfigurationEditViewState,
} from "./AgentConfigurationEditView";
import ExpandAllToggle from "./fields/ExpandAllToggle";
import { useFieldsCollapse } from "./fields/useFieldsCollapse";

type AgentConfigurationEditPanelProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  item: ConfigHistoryItem;
  projectId: string;
  onSaved: (savedVersionName?: string) => void;
};

const AgentConfigurationEditPanel: React.FC<
  AgentConfigurationEditPanelProps
> = ({ open, onOpenChange, item, projectId, onSaved }) => {
  const viewRef = useRef<AgentConfigurationEditViewHandle>(null);
  const [view, setView] = useState<"edit" | "diff">("edit");
  const [description, setDescription] = useState("");
  const [state, setState] = useState<AgentConfigurationEditViewState>({
    isDirty: false,
    isSaving: false,
    hasErrors: false,
    collapsibleKeys: [],
    hasExpandableFields: false,
  });

  const controller = useFieldsCollapse({
    collapsibleKeys: state.collapsibleKeys,
  });

  const handleSavedInternal = (savedName?: string) => {
    onSaved(savedName);
    onOpenChange(false);
  };

  const handleSave = async () => {
    await viewRef.current?.save();
  };

  const handleClose = () => {
    if (state.isSaving) return;
    onOpenChange(false);
    setView("edit");
  };

  const title = "New agent configuration";

  return (
    <Sheet open={open} onOpenChange={(o) => !o && handleClose()}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[872px]"
        header={
          <div className="flex h-12 items-center justify-between border-b px-6">
            <div className="flex items-center gap-2">
              <SheetTitle className="comet-body-accented text-left">
                {title}
              </SheetTitle>
              <Tag
                variant="gray"
                className="flex items-center gap-1 px-1.5 py-1"
              >
                <Pencil className="size-3" />
                From {item.name}
              </Tag>
            </div>
            <Button
              variant="outline"
              size="icon-sm"
              onClick={handleClose}
              aria-label="Close"
            >
              <X className="size-3.5" />
            </Button>
          </div>
        }
      >
        <div className="min-h-0 flex-1 overflow-y-auto px-6">
          {view === "edit" && (
            <Textarea
              placeholder="Add version notes"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="mb-4 min-h-[55px]"
            />
          )}
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h3 className="comet-body-accented flex items-center gap-1">
                {view === "diff" ? (
                  <>
                    Compare {item.name}
                    <ArrowRight className="size-3.5" />
                    current changes
                  </>
                ) : (
                  "Edit fields"
                )}
              </h3>
              <Button
                variant="outline"
                size="2xs"
                onClick={() => setView((v) => (v === "edit" ? "diff" : "edit"))}
                disabled={!state.isDirty && view === "edit"}
              >
                {view === "edit" ? (
                  <>
                    <GitCompare className="mr-1 size-3" />
                    Show diff
                  </>
                ) : (
                  <>
                    <Undo2 className="mr-1 size-3" />
                    Back to edit
                  </>
                )}
              </Button>
            </div>
            {view === "edit" && state.hasExpandableFields && (
              <ExpandAllToggle controller={controller} />
            )}
          </div>

          <AgentConfigurationEditView
            ref={viewRef}
            item={item}
            projectId={projectId}
            onSaved={handleSavedInternal}
            view={view}
            description={description}
            onDescriptionChange={setDescription}
            controller={controller}
            onStateChange={setState}
            blockNavigation
          />
        </div>

        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button
            variant="outline"
            size="sm"
            onClick={handleClose}
            disabled={state.isSaving}
          >
            Cancel
          </Button>
          <Button
            size="sm"
            onClick={handleSave}
            disabled={state.isSaving || state.hasErrors || !state.isDirty}
          >
            {state.isSaving ? "Saving…" : "Save as new version"}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default AgentConfigurationEditPanel;
