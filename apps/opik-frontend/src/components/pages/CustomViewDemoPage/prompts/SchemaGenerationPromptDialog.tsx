/**
 * Dialog for configuring the schema generation AI system prompt
 */

import React, { useState, useRef, useEffect } from "react";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Tag } from "@/components/ui/tag";
import { RotateCcw } from "lucide-react";
import SystemPromptEditor, {
  SystemPromptEditorHandle,
} from "./SystemPromptEditor";
import PromptVariablesPanel from "./PromptVariablesPanel";
import {
  DEFAULT_SCHEMA_GENERATION_PROMPT,
  SCHEMA_GENERATION_VARIABLES,
} from "./promptConstants";
import { PromptDialogProps } from "./types";

const SchemaGenerationPromptDialog: React.FC<PromptDialogProps> = ({
  open,
  onOpenChange,
  currentPrompt,
  onSave,
}) => {
  // Local state for editing
  const [editedPrompt, setEditedPrompt] = useState<string>("");
  const editorRef = useRef<SystemPromptEditorHandle>(null);

  // Initialize/reset when dialog opens
  useEffect(() => {
    if (open) {
      setEditedPrompt(currentPrompt ?? DEFAULT_SCHEMA_GENERATION_PROMPT);
    }
  }, [open, currentPrompt]);

  const isCustomized = currentPrompt !== null;
  const hasChanges =
    editedPrompt !== (currentPrompt ?? DEFAULT_SCHEMA_GENERATION_PROMPT);

  const handleInsertVariable = (variableName: string) => {
    editorRef.current?.insertAtCursor(`{{${variableName}}}`);
    editorRef.current?.focus();
  };

  const handleSave = () => {
    // Save as null if it matches the default (reset to default)
    const promptToSave =
      editedPrompt === DEFAULT_SCHEMA_GENERATION_PROMPT ? null : editedPrompt;
    onSave(promptToSave);
    onOpenChange(false);
  };

  const handleResetToDefault = () => {
    setEditedPrompt(DEFAULT_SCHEMA_GENERATION_PROMPT);
  };

  const handleCancel = () => {
    onOpenChange(false);
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <DialogTitle>Schema Generation AI Prompt</DialogTitle>
            {isCustomized && (
              <Tag variant="purple" size="sm">
                Customized
              </Tag>
            )}
          </div>
          <DialogDescription>
            Customize the system prompt used for generating ViewTree schemas
            that define the visual layout.
          </DialogDescription>
        </DialogHeader>

        <DialogAutoScrollBody>
          <div className="grid gap-4 py-4">
            <SystemPromptEditor
              ref={editorRef}
              value={editedPrompt}
              onChange={setEditedPrompt}
              placeholder="Enter your system prompt..."
              minHeight="250px"
            />

            <PromptVariablesPanel
              variables={SCHEMA_GENERATION_VARIABLES}
              onInsertVariable={handleInsertVariable}
            />
          </div>
        </DialogAutoScrollBody>

        <DialogFooter className="flex items-center justify-between sm:justify-between">
          <Button
            variant="ghost"
            onClick={handleResetToDefault}
            disabled={editedPrompt === DEFAULT_SCHEMA_GENERATION_PROMPT}
          >
            <RotateCcw className="mr-2 size-4" />
            Reset to Default
          </Button>
          <div className="flex gap-2">
            <Button variant="outline" onClick={handleCancel}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={!hasChanges}>
              Save
            </Button>
          </div>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default SchemaGenerationPromptDialog;
