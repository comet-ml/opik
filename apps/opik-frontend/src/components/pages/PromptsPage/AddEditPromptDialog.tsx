import React, { useCallback, useState } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";
import { useNavigate } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Description } from "@/components/ui/description";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertTitle } from "@/components/ui/alert";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
  Accordion,
} from "@/components/ui/accordion";
import { Prompt } from "@/types/prompts";
import usePromptCreateMutation from "@/api/prompts/usePromptCreateMutation";
import usePromptUpdateMutation from "@/api/prompts/usePromptUpdateMutation";
import { isValidJsonObject, safelyParseJSON } from "@/lib/utils";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import { useBooleanTimeoutState } from "@/hooks/useBooleanTimeoutState";
import useAppStore from "@/store/AppStore";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import PromptMessageImageTags from "@/components/pages-shared/llm/PromptMessageImageTags/PromptMessageImageTags";
import { useMessageContent } from "@/hooks/useMessageContent";

type AddPromptDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  prompt?: Prompt;
};

const AddEditPromptDialog: React.FunctionComponent<AddPromptDialogProps> = ({
  open,
  setOpen,
  prompt: defaultPrompt,
}) => {
  const { t } = useTranslation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const [name, setName] = useState(defaultPrompt?.name || "");
  const [template, setTemplate] = useState("");
  const [metadata, setMetadata] = useState("");
  const [description, setDescription] = useState(
    defaultPrompt?.description || "",
  );

  const [showInvalidJSON, setShowInvalidJSON] = useBooleanTimeoutState({});
  const theme = useCodemirrorTheme({
    editable: true,
  });

  const { localText, images, setImages, handleContentChange } =
    useMessageContent({
      content: template,
      onChangeContent: setTemplate,
    });

  const { mutate: createMutate } = usePromptCreateMutation();
  const { mutate: updateMutate } = usePromptUpdateMutation();

  const isEdit = !!defaultPrompt;
  const isValid = Boolean(name.length && (isEdit || template.length));
  const title = isEdit ? t("prompts.dialog.editTitle") : t("prompts.dialog.createTitle");
  const submitText = isEdit ? t("prompts.dialog.updateButton") : t("prompts.dialog.createButton");

  const onPromptCreated = useCallback(
    (prompt: Prompt) => {
      if (!prompt.id) return;

      navigate({
        to: "/$workspaceName/prompts/$promptId",
        params: {
          promptId: prompt.id,
          workspaceName,
        },
      });
    },
    [workspaceName, navigate],
  );

  const createPrompt = useCallback(() => {
    const isMetadataValid = metadata === "" || isValidJsonObject(metadata);

    if (!isMetadataValid) {
      return setShowInvalidJSON(true);
    }

    createMutate(
      {
        prompt: {
          name,
          template,
          ...(metadata && { metadata: safelyParseJSON(metadata) }),
          ...(description && { description }),
        },
      },
      { onSuccess: onPromptCreated },
    );
    setOpen(false);
  }, [
    metadata,
    createMutate,
    name,
    template,
    description,
    setOpen,
    setShowInvalidJSON,
    onPromptCreated,
  ]);

  const editPrompt = useCallback(() => {
    updateMutate({
      prompt: {
        id: defaultPrompt?.id,
        name,
        ...(description ? { description } : {}),
      },
    });
    setOpen(false);
  }, [updateMutate, defaultPrompt?.id, name, description, setOpen]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[720px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          {!isEdit && (
            <ExplainerDescription
              className="mb-4"
              {...EXPLAINERS_MAP[EXPLAINER_ID.how_do_i_write_my_prompt]}
              description={t("prompts.explainers.howDoIWriteMyPrompt")}
            />
          )}
          <div className="flex flex-col gap-2 pb-4">
            <Label htmlFor="promptName">{t("prompts.dialog.name")}</Label>
            <Input
              id="promptName"
              placeholder={t("prompts.dialog.namePlaceholder")}
              value={name}
              onChange={(event) => setName(event.target.value)}
            />
          </div>
          {!isEdit && (
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor="template">{t("prompts.dialog.prompt")}</Label>
              <Textarea
                id="template"
                className="comet-code"
                placeholder={t("prompts.dialog.promptPlaceholder")}
                value={localText}
                onChange={(event) => handleContentChange(event.target.value)}
              />
              <Description>
                {t("prompts.explainers.whatFormatShouldThePromptBe")}
              </Description>
            </div>
          )}
          {!isEdit && (
            <div className="flex flex-col gap-2 pb-4">
              <Label>{t("prompts.dialog.images")}</Label>
              <PromptMessageImageTags
                images={images}
                setImages={setImages}
                align="start"
              />
            </div>
          )}
          <div className="flex flex-col gap-2 border-t border-border pb-4">
            <Accordion
              type="multiple"
              defaultValue={
                defaultPrompt?.description ? ["description"] : undefined
              }
            >
              {!isEdit && (
                <AccordionItem value="metadata">
                  <AccordionTrigger>{t("prompts.dialog.metadata")}</AccordionTrigger>
                  <AccordionContent>
                    <div className="max-h-40 overflow-y-auto rounded-md">
                      <CodeMirror
                        theme={theme}
                        value={metadata}
                        onChange={setMetadata}
                        extensions={[jsonLanguage, EditorView.lineWrapping]}
                      />
                    </div>
                    <Description className="mt-2 block">
                      {t("prompts.explainers.whatFormatShouldTheMetadataBe")}
                    </Description>
                  </AccordionContent>
                </AccordionItem>
              )}
              {showInvalidJSON && (
                <Alert variant="destructive">
                  <AlertTitle>{t("prompts.dialog.metadataInvalid")}</AlertTitle>
                </Alert>
              )}
              <AccordionItem value="description">
                <AccordionTrigger>{t("prompts.dialog.description")}</AccordionTrigger>
                <AccordionContent>
                  <Textarea
                    id="promptDescription"
                    placeholder={t("prompts.dialog.descriptionPlaceholder")}
                    value={description}
                    onChange={(event) => setDescription(event.target.value)}
                    maxLength={255}
                  />
                </AccordionContent>
              </AccordionItem>
            </Accordion>
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t("common.cancel")}</Button>
          </DialogClose>
          <Button
            type="submit"
            disabled={!isValid}
            onClick={isEdit ? editPrompt : createPrompt}
          >
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditPromptDialog;
