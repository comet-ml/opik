import React, { useCallback, useEffect, useMemo, useState } from "react";
import { Plus, X } from "lucide-react";
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
import { Separator } from "@/components/ui/separator";
import { Description } from "@/components/ui/description";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import ColorPicker from "@/components/shared/ColorPicker/ColorPicker";
import WorkspaceColorsAutocomplete from "@/components/pages-shared/WorkspaceColorsAutocomplete/WorkspaceColorsAutocomplete";
import { DEFAULT_HEX_COLOR, HEX_COLOR_REGEX } from "@/constants/colorVariants";
import { COLOR_MAP_MAX_ENTRIES } from "@/hooks/useUpdateColorMapping";

type EditColorMapDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  colorMap: Record<string, string> | null;
  onUpdate: (colorMap: Record<string, string>) => void;
};

const EditColorMapDialog: React.FC<EditColorMapDialogProps> = ({
  open,
  setOpen,
  colorMap,
  onUpdate,
}) => {
  const [localMap, setLocalMap] = useState<Record<string, string>>(
    () => colorMap ?? {},
  );
  const [adding, setAdding] = useState(false);
  const [addName, setAddName] = useState("");
  const [addColor, setAddColor] = useState(DEFAULT_HEX_COLOR);

  useEffect(() => {
    if (open) {
      setLocalMap(colorMap ?? {});
      setAdding(false);
      setAddName("");
      setAddColor(DEFAULT_HEX_COLOR);
    }
  }, [open, colorMap]);

  const entries = useMemo(() => {
    return Object.entries(localMap)
      .map(([name, color]) => ({ name, color }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [localMap]);

  const existingNames = useMemo(() => entries.map((e) => e.name), [entries]);

  const resetAdd = useCallback(() => {
    setAdding(false);
    setAddName("");
    setAddColor(DEFAULT_HEX_COLOR);
  }, []);

  const handleAddConfirm = useCallback(() => {
    const trimmed = addName.trim();
    if (!trimmed || !HEX_COLOR_REGEX.test(addColor)) return;
    if (existingNames.some((n) => n.toLowerCase() === trimmed.toLowerCase()))
      return;

    setLocalMap((prev) => ({ ...prev, [trimmed]: addColor }));
    resetAdd();
  }, [addName, addColor, existingNames, resetAdd]);

  const handleNameChange = useCallback(
    (oldName: string, newName: string) => {
      const trimmed = newName.trim();
      if (!trimmed || trimmed === oldName) return;
      if (
        existingNames.some(
          (n) => n !== oldName && n.toLowerCase() === trimmed.toLowerCase(),
        )
      )
        return;

      setLocalMap((prev) => {
        const updated = { ...prev };
        const color = updated[oldName];
        delete updated[oldName];
        updated[trimmed] = color;
        return updated;
      });
    },
    [existingNames],
  );

  const handleColorChange = useCallback((name: string, color: string) => {
    if (!HEX_COLOR_REGEX.test(color)) return;
    setLocalMap((prev) => ({ ...prev, [name]: color }));
  }, []);

  const handleDelete = useCallback((name: string) => {
    setLocalMap((prev) => {
      const updated = { ...prev };
      delete updated[name];
      return updated;
    });
  }, []);

  const handleSave = useCallback(() => {
    onUpdate(localMap);
    setOpen(false);
  }, [localMap, onUpdate, setOpen]);

  const trimmedAddName = addName.trim();
  const isAddNameDuplicate = existingNames.some(
    (n) => n.toLowerCase() === trimmedAddName.toLowerCase(),
  );
  const isAddValid =
    trimmedAddName.length > 0 &&
    !isAddNameDuplicate &&
    HEX_COLOR_REGEX.test(addColor);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent
        className="max-w-lg"
        onOpenAutoFocus={(e) => e.preventDefault()}
      >
        <DialogHeader>
          <DialogTitle>Color mappings</DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody>
          {entries.length > 0 || adding ? (
            <div className="flex flex-col gap-1">
              {entries.map((entry) => (
                <div key={entry.name} className="flex items-center gap-1">
                  <div className="min-w-0 flex-1">
                    <WorkspaceColorsAutocomplete
                      value={entry.name}
                      onValueChange={(v) => handleNameChange(entry.name, v)}
                      excludeNames={existingNames.filter(
                        (n) => n !== entry.name,
                      )}
                      placeholder="Type or select a name..."
                    />
                  </div>
                  <Popover>
                    <PopoverTrigger asChild>
                      <button
                        type="button"
                        className="size-9 shrink-0 rounded-md border border-border"
                        style={{ backgroundColor: entry.color }}
                      />
                    </PopoverTrigger>
                    <PopoverContent className="w-auto" align="start">
                      <ColorPicker
                        value={entry.color}
                        onChange={(c) => handleColorChange(entry.name, c)}
                      />
                    </PopoverContent>
                  </Popover>
                  <Button
                    variant="minimal"
                    size="icon-xs"
                    className="shrink-0"
                    onClick={() => handleDelete(entry.name)}
                  >
                    <X />
                  </Button>
                </div>
              ))}
              {adding && (
                <div className="flex flex-col gap-1">
                  <div className="flex items-center gap-1">
                    <div className="min-w-0 flex-1">
                      <WorkspaceColorsAutocomplete
                        value={addName}
                        onValueChange={setAddName}
                        excludeNames={existingNames}
                        placeholder="Type or select a name..."
                        hasError={isAddNameDuplicate}
                      />
                    </div>
                    <Popover>
                      <PopoverTrigger asChild>
                        <button
                          type="button"
                          className="size-9 shrink-0 rounded-md border border-border"
                          style={{ backgroundColor: addColor }}
                        />
                      </PopoverTrigger>
                      <PopoverContent className="w-auto" align="start">
                        <ColorPicker value={addColor} onChange={setAddColor} />
                      </PopoverContent>
                    </Popover>
                    <Button
                      variant="minimal"
                      size="icon-xs"
                      className="shrink-0"
                      onClick={isAddValid ? handleAddConfirm : resetAdd}
                    >
                      <X />
                    </Button>
                  </div>
                  {isAddNameDuplicate && (
                    <p className="comet-body-xs text-destructive">
                      A mapping with this name already exists
                    </p>
                  )}
                </div>
              )}
            </div>
          ) : (
            <Description>
              Assign custom colors to labels used in feedback scores, charts,
              and experiments. Labels without a mapping will use automatically
              assigned colors.
            </Description>
          )}
          <Separator className="my-2" />
          <Button
            variant="secondary"
            onClick={() => setAdding(true)}
            disabled={adding || entries.length >= COLOR_MAP_MAX_ENTRIES}
          >
            <Plus className="mr-2 size-4" />
            Add color
          </Button>
        </DialogAutoScrollBody>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">Cancel</Button>
          </DialogClose>
          <Button variant="default" onClick={handleSave}>
            Save
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditColorMapDialog;
