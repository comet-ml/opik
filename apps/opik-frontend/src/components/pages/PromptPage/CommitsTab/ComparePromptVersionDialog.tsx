import React, { useEffect, useMemo, useState } from "react";
import last from "lodash/last";
import first from "lodash/first";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";
import { PromptVersion } from "@/types/prompts";
import { cn } from "@/lib/utils";
import { formatDate } from "@/lib/date";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import PromptMessageImageTags from "@/components/pages-shared/llm/PromptMessageImageTags/PromptMessageImageTags";
import { parseContentWithImages } from "@/lib/llm";

type ComparePromptVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  versions: PromptVersion[];
};

const ComparePromptVersionDialog: React.FunctionComponent<
  ComparePromptVersionDialogProps
> = ({ open, setOpen, versions }) => {
  const [baseVersion, setBaseVersion] = useState<PromptVersion | undefined>(
    last(versions),
  );
  const [diffVersion, setDiffVersion] = useState<PromptVersion | undefined>(
    first(versions),
  );

  const { text: baseText, images: baseImages } = useMemo(
    () => parseContentWithImages(baseVersion?.template || ""),
    [baseVersion?.template],
  );

  const { text: diffText, images: diffImages } = useMemo(
    () => parseContentWithImages(diffVersion?.template || ""),
    [diffVersion?.template],
  );

  const imagesHaveChanges = useMemo(
    () => JSON.stringify(baseImages) !== JSON.stringify(diffImages),
    [baseImages, diffImages],
  );

  const hasMoreThenTwoVersions = versions?.length > 2;

  const versionOptions = useMemo(() => {
    return versions
      .sort((v1, v2) => v1.created_at.localeCompare(v2.created_at))
      .map((v) => ({
        label: v.commit,
        value: v.commit,
        description: formatDate(v.created_at),
      }));
  }, [versions]);

  useEffect(() => {
    if (open) {
      setBaseVersion(
        versions.find((v) => v.commit === first(versionOptions)?.value),
      );
      setDiffVersion(
        versions.find((v) => v.commit === last(versionOptions)?.value),
      );
    }
  }, [open, versionOptions, versions]);

  const generateTitle = (
    version: PromptVersion | undefined,
    setter: React.Dispatch<React.SetStateAction<PromptVersion | undefined>>,
    disabledValue?: string,
  ) => {
    if (!version) return;

    if (hasMoreThenTwoVersions) {
      return (
        <div>
          <SelectBox
            value={version?.commit}
            options={versionOptions.map((o) => ({
              ...o,
              disabled: o.value === disabledValue,
            }))}
            onChange={(value) =>
              setter(versions.find((v) => v.commit === value))
            }
            renderTrigger={(value) => {
              const option = versionOptions.find((o) => o.value === value);
              return (
                <span>
                  {option?.label} ({option?.description})
                </span>
              );
            }}
          ></SelectBox>
        </div>
      );
    } else {
      return (
        <div className="-mb-2 px-0.5">
          <span className="comet-body-s-accented mr-2">{version.commit}</span>
          <span className="comet-body-s text-light-slate">
            {formatDate(version.created_at)}
          </span>
        </div>
      );
    }
  };

  const generateDiffView = (c1: string, c2: string) => {
    return (
      <div
        className={cn(
          "comet-code overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5",
          imagesHaveChanges ? "h-[520px]" : "h-[620px]",
        )}
      >
        <TextDiff content1={c1} content2={c2} />
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[880px]">
        <DialogHeader>
          <DialogTitle>Compare prompts</DialogTitle>
        </DialogHeader>
        <div className="flex flex-col gap-4 pb-2">
          <div className="grid grid-cols-2 gap-4">
            {generateTitle(baseVersion, setBaseVersion, diffVersion?.commit)}
            {generateTitle(diffVersion, setDiffVersion, baseVersion?.commit)}
            {generateDiffView(baseText, baseText)}
            {generateDiffView(baseText, diffText)}
          </div>
          {imagesHaveChanges && (
            <div className="grid grid-cols-2 gap-4">
              <div className="flex items-center gap-2 rounded-md border p-4">
                <PromptMessageImageTags
                  images={baseImages}
                  setImages={() => {}}
                  align="start"
                  editable={false}
                  preview={true}
                />
              </div>
              <div className="flex items-center gap-2 rounded-md border p-4">
                <PromptMessageImageTags
                  images={diffImages}
                  setImages={() => {}}
                  align="start"
                  editable={false}
                  preview={true}
                />
              </div>
            </div>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default ComparePromptVersionDialog;
