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
import { formatDate } from "@/lib/date";
import SelectBox from "@/components/shared/SelectBox/SelectBox";

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
      <div className="comet-code h-[620px] overflow-y-auto whitespace-pre-line break-words rounded-md border px-2.5 py-1.5">
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
        <div className="grid grid-cols-2 gap-4 pb-2">
          {generateTitle(baseVersion, setBaseVersion, diffVersion?.commit)}
          {generateTitle(diffVersion, setDiffVersion, baseVersion?.commit)}
          {generateDiffView(
            baseVersion?.template ?? "",
            baseVersion?.template ?? "",
          )}
          {generateDiffView(
            baseVersion?.template ?? "",
            diffVersion?.template ?? "",
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default ComparePromptVersionDialog;
