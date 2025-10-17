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
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import useCreatePromptVersionMutation from "@/api/prompts/useCreatePromptVersionMutation";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { Description } from "@/components/ui/description";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import PromptMessageImageTags from "@/components/pages-shared/llm/PromptMessageImageTags/PromptMessageImageTags";
import { useMessageContent } from "@/hooks/useMessageContent";
import { parseContentWithImages } from "@/lib/llm";

enum PROMPT_PREVIEW_MODE {
  write = "write",
  diff = "diff",
}

type EditPromptVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  template: string;
  metadata?: object;
  promptName: string;
  onSetActiveVersionId: (versionId: string) => void;
};

const EditPromptVersionDialog: React.FunctionComponent<
  EditPromptVersionDialogProps
> = ({
  open,
  setOpen,
  template: promptTemplate,
  metadata: promptMetadata,
  promptName,
  onSetActiveVersionId,
}) => {
  const [previewMode, setPreviewMode] = useState<PROMPT_PREVIEW_MODE>(
    PROMPT_PREVIEW_MODE.write,
  );
  const metadataString = promptMetadata
    ? JSON.stringify(promptMetadata, null, 2)
    : "";
  const [template, setTemplate] = useState(promptTemplate);
  const [metadata, setMetadata] = useState(metadataString);
  const [changeDescription, setChangeDescription] = useState("");

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { localText, images, setImages, handleContentChange } =
    useMessageContent({
      content: template,
      onChangeContent: setTemplate,
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
      changeDescription,
      ...(metadata && { metadata: safelyParseJSON(metadata) }),
      onSuccess: (data) => {
        onSetActiveVersionId(data.id);
      },
    });

    setOpen(false);
  };

  const templateHasChanges = template !== promptTemplate;
  const metadataHasChanges = metadata !== metadataString;
  const isValid =
    template?.length && (templateHasChanges || metadataHasChanges);

  const { text: originalText, images: originalImages } =
    parseContentWithImages(promptTemplate);
  const { text: currentText, images: currentImages } =
    parseContentWithImages(template);

  const imagesHaveChanges =
    JSON.stringify(originalImages) !== JSON.stringify(currentImages);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>Edit prompt</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <ExplainerDescription
            className="mb-4"
            {...EXPLAINERS_MAP[EXPLAINER_ID.what_happens_if_i_edit_my_prompt]}
          />
          <div className="flex flex-col gap-2 pb-4">
            <div className="mt-3 flex items-center justify-between">
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
              <>
                <Textarea
                  id="template"
                  className="comet-code"
                  placeholder="Prompt"
                  value={localText}
                  onChange={(event) => handleContentChange(event.target.value)}
                />
                <Description>
                  {
                    EXPLAINERS_MAP[
                      EXPLAINER_ID.what_format_should_the_prompt_be
                    ].description
                  }
                </Description>
              </>
            ) : (
              <div className="flex flex-col gap-4">
                <div className="comet-code min-h-44 overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5">
                  <TextDiff content1={originalText} content2={currentText} />
                </div>
                {imagesHaveChanges && (
                  <div className="flex flex-col gap-3 rounded-md border p-4">
                    <div className="comet-body-s-accented text-muted-foreground">
                      Images comparison
                    </div>
                    <div className="flex flex-col gap-2">
                      <div className="comet-body-xs text-muted-foreground">
                        Before:
                      </div>
                      <PromptMessageImageTags
                        images={originalImages}
                        setImages={() => {}}
                        align="start"
                        editable={false}
                      />
                    </div>
                    <div className="flex flex-col gap-2">
                      <div className="comet-body-xs text-muted-foreground">
                        After:
                      </div>
                      <PromptMessageImageTags
                        images={currentImages}
                        setImages={() => {}}
                        align="start"
                        editable={false}
                      />
                    </div>
                  </div>
                )}
              </div>
            )}
          </div>
          {previewMode === PROMPT_PREVIEW_MODE.write && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>Images</Label>
              <PromptMessageImageTags
                images={images}
                setImages={setImages}
                align="start"
              />
            </div>
          )}
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="promptMetadata">Commit message</Label>
            <Textarea
              className="comet-code min-h-20"
              id="promptMetadata"
              value={changeDescription}
              onChange={(e) => setChangeDescription(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-2 border-t border-border pb-4">
            <Accordion type="multiple">
              <AccordionItem value="metadata">
                <AccordionTrigger>Metadata</AccordionTrigger>
                <AccordionContent>
                  <div className="rounded-md">
                    <CodeMirror
                      theme={theme}
                      value={metadata}
                      onChange={setMetadata}
                      extensions={[jsonLanguage, EditorView.lineWrapping]}
                    />
                  </div>
                  <Description className="mt-2 block">
                    {
                      EXPLAINERS_MAP[
                        EXPLAINER_ID.what_format_should_the_metadata_be
                      ].description
                    }
                  </Description>
                </AccordionContent>
              </AccordionItem>
              {showInvalidJSON && (
                <Alert variant="destructive">
                  <AlertTitle>Metadata field is not valid</AlertTitle>
                </Alert>
              )}
            </Accordion>
          </div>
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

export default EditPromptVersionDialog;
