import React, { useCallback, useEffect, useMemo, useState } from "react";
import { AxiosError } from "axios";

import useEnvironmentCreateMutation from "@/api/environments/useEnvironmentCreateMutation";
import useEnvironmentUpdateMutation from "@/api/environments/useEnvironmentUpdateMutation";
import ColorPicker from "@/shared/ColorPicker/ColorPicker";
import { getDefaultEnvironmentMeta } from "@/shared/EnvironmentLabel/helpers";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogAutoScrollBody,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Input } from "@/ui/input";
import { Label } from "@/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Textarea } from "@/ui/textarea";
import { HEX_COLOR_REGEX, PRESET_HEX_COLORS } from "@/constants/colorVariants";
import {
  ENVIRONMENT_DESCRIPTION_MAX_LENGTH,
  ENVIRONMENT_NAME_MAX_LENGTH,
  ENVIRONMENT_NAME_REGEX,
  Environment,
} from "@/types/environments";
import { extractErrorMessage } from "@/lib/errors";

type EnvironmentDialogMode = "create" | "edit" | "clone";

type AddEditEnvironmentDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  environment?: Environment;
  mode?: EnvironmentDialogMode;
};

const AddEditEnvironmentDialog: React.FunctionComponent<
  AddEditEnvironmentDialogProps
> = ({ open, setOpen, environment, mode = "create" }) => {
  const [name, setName] = useState<string>(
    mode === "clone" && environment
      ? `${environment.name}-copy`
      : environment?.name ?? "",
  );
  const [description, setDescription] = useState<string>(
    environment?.description ?? "",
  );
  const [color, setColor] = useState<string>(() =>
    environment?.color && HEX_COLOR_REGEX.test(environment.color)
      ? environment.color
      : PRESET_HEX_COLORS[Math.floor(Math.random() * PRESET_HEX_COLORS.length)],
  );
  const [submitError, setSubmitError] = useState<string>("");
  const [colorPopoverOpen, setColorPopoverOpen] = useState(false);

  const { mutate: createMutation } = useEnvironmentCreateMutation({
    showErrorToast: false,
  });
  const { mutate: updateMutation } = useEnvironmentUpdateMutation({
    showErrorToast: false,
  });

  const trimmedName = name.trim();
  const nameError = useMemo(() => {
    if (!trimmedName) return "";
    if (trimmedName.length > ENVIRONMENT_NAME_MAX_LENGTH) {
      return `Name must be ${ENVIRONMENT_NAME_MAX_LENGTH} characters or less.`;
    }
    if (!ENVIRONMENT_NAME_REGEX.test(trimmedName)) {
      return "Use only letters, numbers, dashes, and underscores.";
    }
    return "";
  }, [trimmedName]);

  // Color is locked while the name matches a seeded default (development /
  // staging / production) so the badge icon + color stay consistent with what
  // the backend seeds in migration 000066. Renaming away from the default
  // unlocks the picker.
  const lockedDefault = getDefaultEnvironmentMeta(trimmedName);
  const isColorLocked = lockedDefault !== null;

  useEffect(() => {
    if (lockedDefault && color !== lockedDefault.color) {
      setColor(lockedDefault.color);
    }
  }, [lockedDefault, color]);

  const LockedIcon = lockedDefault?.icon;

  const isEdit = mode === "edit";
  const title =
    mode === "clone"
      ? "Clone environment"
      : isEdit
        ? "Edit environment"
        : "Create a new environment";
  const submitText = isEdit ? "Update environment" : "Create environment";

  const isValid = trimmedName.length > 0 && !nameError;

  const handleNameChange = useCallback((value: string) => {
    setName(value);
    setSubmitError("");
  }, []);

  const handleDescriptionChange = useCallback((value: string) => {
    setDescription(value);
    setSubmitError("");
  }, []);

  const handleColorChange = useCallback((value: string) => {
    setColor(value);
    setSubmitError("");
  }, []);

  const submitHandler = useCallback(() => {
    if (!isValid) return;

    const payload = {
      name: trimmedName,
      description: description.trim() || null,
      color,
    };

    const mutationOptions = {
      onSuccess: () => setOpen(false),
      onError: (error: AxiosError) => {
        setSubmitError(extractErrorMessage(error));
      },
    };

    if (isEdit && environment) {
      updateMutation(
        { environmentId: environment.id, environment: payload },
        mutationOptions,
      );
    } else {
      createMutation({ environment: payload }, mutationOptions);
    }
  }, [
    isValid,
    trimmedName,
    description,
    color,
    isEdit,
    environment,
    createMutation,
    updateMutation,
    setOpen,
  ]);

  const handleBodyKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
        event.preventDefault();
        submitHandler();
      }
    },
    [submitHandler],
  );

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogAutoScrollBody>
          <div onKeyDown={handleBodyKeyDown}>
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor="environmentName">Name</Label>
              <div className="flex items-center">
                {isColorLocked ? (
                  <TooltipWrapper content="Color is reserved for the default environment">
                    <div
                      aria-label="Default environment color (locked)"
                      className="flex size-10 shrink-0 cursor-not-allowed items-center justify-center rounded-l-md"
                      style={{ backgroundColor: color }}
                    >
                      {LockedIcon && (
                        <LockedIcon className="size-5 text-white" />
                      )}
                    </div>
                  </TooltipWrapper>
                ) : (
                  <Popover
                    open={colorPopoverOpen}
                    onOpenChange={setColorPopoverOpen}
                  >
                    <PopoverTrigger asChild>
                      <button
                        type="button"
                        aria-label="Change color"
                        className="size-10 shrink-0 rounded-l-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
                        style={{ backgroundColor: color }}
                      />
                    </PopoverTrigger>
                    <PopoverContent className="w-auto" align="start">
                      <ColorPicker
                        value={color}
                        onChange={handleColorChange}
                        onPresetSelect={() => setColorPopoverOpen(false)}
                      />
                    </PopoverContent>
                  </Popover>
                )}
                <Input
                  id="environmentName"
                  className="flex-1 rounded-l-none"
                  placeholder="e.g. staging"
                  value={name}
                  onChange={(event) => handleNameChange(event.target.value)}
                  maxLength={ENVIRONMENT_NAME_MAX_LENGTH}
                />
              </div>
              {nameError || submitError ? (
                <p className="comet-body-xs text-destructive">
                  {nameError || submitError}
                </p>
              ) : (
                <p className="comet-body-xs text-light-slate">
                  Use letters, numbers, dashes, or underscores. Must be unique
                  within the workspace.
                </p>
              )}
            </div>
            <div className="flex flex-col gap-2 pb-4">
              <Label htmlFor="environmentDescription">Description</Label>
              <Textarea
                id="environmentDescription"
                placeholder="Optional description"
                className="min-h-20"
                value={description}
                onChange={(event) =>
                  handleDescriptionChange(event.target.value)
                }
                maxLength={ENVIRONMENT_DESCRIPTION_MAX_LENGTH}
              />
            </div>
          </div>
        </DialogAutoScrollBody>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button type="submit" disabled={!isValid} onClick={submitHandler}>
            {submitText}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddEditEnvironmentDialog;
