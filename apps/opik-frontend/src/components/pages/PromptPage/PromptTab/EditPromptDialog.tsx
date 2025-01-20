import React, { useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { jsonLanguage } from "@codemirror/lang-json";
import { EditorView } from "@codemirror/view";

import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Alert, AlertTitle } from "@/components/ui/alert";
import { Textarea } from "@/components/ui/textarea";
import { Label } from "@/components/ui/label";
import { Button } from "@/components/ui/button";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";

enum PROMPT_PREVIEW_MODE {
  write = "write",
  diff = "diff",
}

type EditPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  template: string;
  metadata?: object;
  promptName: string;
  onSetActiveVersionId: (versionId: string) => void;
};

const EditPromptDialog: React.FunctionComponent<EditPromptDialogProps> = ({
  open,
  setOpen,
  template: promptTemplate,
  metadata: promptMetadata,
  promptName,
  onSetActiveVersionId,
}) => {
  const [tab, setTab] = useState("template");
  const [previewMode, setPreviewMode] = useState<PROMPT_PREVIEW_MODE>(
    PROMPT_PREVIEW_MODE.write,
  );
  const metadataString = promptMetadata
    ? JSON.stringify(promptMetadata, null, 2)
    : "";
  const [template, setTemplate] = useState(promptTemplate);
  const [metadata, setMetadata] = useState(metadataString);

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { mutate } = useCreatePromptVersionMutation();

  const handleClickEditPrompt = () => {
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    mutate({
      name: promptName,
      template,
      ...(metadata && { metadata: safelyParseJSON(metadata) }),
      onSetActiveVersionId,
    });

    setOpen(false);
  };

  const templateHasChanges = template !== promptTemplate;
  const metadataHasChanges = metadata !== metadataString;
  const isValid =
    template?.length && (templateHasChanges || metadataHasChanges);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Edit prompt</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <p className="comet-body-s text-muted-slate ">
            By editing a prompt, a new commit will be created automatically. You
            can access older versions of the prompt from the <b>Commits</b> tab.
          </p>

          <Tabs
            defaultValue="template"
            value={tab}
            onValueChange={setTab}
            className="my-2"
          >
            <TabsList variant="underline">
              <TabsTrigger variant="underline" value="template">
                Template
              </TabsTrigger>
              <TabsTrigger variant="underline" value="metadata">
                Metadata
              </TabsTrigger>
            </TabsList>
            <TabsContent value="template" className="h-[422px]">
              <div className="mb-3 flex items-center justify-between">
                <Label htmlFor="promptTemplate">Prompt</Label>
                <ToggleGroup
                  type="single"
                  value={previewMode}
                  onValueChange={(value) =>
                    value && setPreviewMode(value as PROMPT_PREVIEW_MODE)
                  }
                  size="sm"
                >
                  <ToggleGroupItem
                    value={PROMPT_PREVIEW_MODE.write}
                    aria-label="Write"
                  >
                    Write
                  </ToggleGroupItem>
                  <ToggleGroupItem
                    value={PROMPT_PREVIEW_MODE.diff}
                    aria-label="Preview changes"
                    disabled={!templateHasChanges}
                  >
                    Preview changes
                  </ToggleGroupItem>
                </ToggleGroup>
              </div>
              {previewMode === PROMPT_PREVIEW_MODE.write ? (
                <Textarea
                  className="comet-code h-[350px] resize-none"
                  id="promptTemplate"
                  value={template}
                  onChange={(e) => setTemplate(e.target.value)}
                />
              ) : (
                <div className="comet-code h-[350px] overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5">
                  <TextDiff content1={promptTemplate} content2={template} />
                </div>
              )}
              <p className="comet-body-xs mt-3 text-light-slate">
                You can specify variables using the &quot;mustache&quot; syntax:{" "}
                {"{{variable}}"}.
              </p>
            </TabsContent>
            <TabsContent value="metadata" className="h-[422px]">
              <div className="h-[382px] overflow-y-auto rounded-md">
                <CodeMirror
                  theme={theme}
                  value={metadata}
                  onChange={setMetadata}
                  extensions={[jsonLanguage, EditorView.lineWrapping]}
                />
              </div>
              <p className="comet-body-xs mt-2 text-light-slate">
                You can specify only valid JSON object.
              </p>
            </TabsContent>
          </Tabs>
          {showInvalidJSON && (
            <Alert variant="destructive">
              <AlertTitle>Metadata field is not valid</AlertTitle>
            </Alert>
          )}
        </DialogAutoScrollBody>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={!isValid}
            onClick={handleClickEditPrompt}
          >
            Create new commit
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditPromptDialog;
