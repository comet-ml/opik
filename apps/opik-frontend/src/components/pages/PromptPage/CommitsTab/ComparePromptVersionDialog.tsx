import React, { useEffect, useMemo, useState } from "react";
import last from "lodash/last";
import first from "lodash/first";
import isEqual from "fast-deep-equal";

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
import { parseLLMMessageContent, parsePromptVersionContent } from "@/lib/llm";
import MediaTagsList from "@/components/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import { SelectItem } from "@/components/ui/select";
import { DropdownOption } from "@/types/shared";
import VersionTags from "@/components/pages/PromptPage/PromptTab/VersionTags";

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

  const baseText = useMemo(
    () => baseVersion?.template || "",
    [baseVersion?.template],
  );

  const {
    images: baseImages,
    videos: baseVideos,
    audios: baseAudios,
  } = useMemo(() => {
    const content = parsePromptVersionContent(baseVersion);
    return parseLLMMessageContent(content);
  }, [baseVersion]);

  const diffText = useMemo(
    () => diffVersion?.template || "",
    [diffVersion?.template],
  );

  const {
    images: diffImages,
    videos: diffVideos,
    audios: diffAudios,
  } = useMemo(() => {
    const content = parsePromptVersionContent(diffVersion);
    return parseLLMMessageContent(content);
  }, [diffVersion]);

  const imagesHaveChanges = useMemo(
    () => !isEqual(baseImages, diffImages),
    [baseImages, diffImages],
  );
  const videosHaveChanges = useMemo(
    () => !isEqual(baseVideos, diffVideos),
    [baseVideos, diffVideos],
  );
  const audiosHaveChanges = useMemo(
    () => !isEqual(baseAudios, diffAudios),
    [baseAudios, diffAudios],
  );

  const hasMoreThenTwoVersions = versions?.length > 2;

  const versionOptions = useMemo(() => {
    return versions
      .sort((v1, v2) => v1.created_at.localeCompare(v2.created_at))
      .map((v) => ({
        label: v.commit,
        value: v.commit,
        description: formatDate(v.created_at),
        tags: v.tags || [],
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

  const renderTagsWithSeparator = (tags: string[] | undefined) => {
    if (!tags || tags.length === 0) return null;

    return (
      <>
        <span className="shrink-0 text-xs text-muted-slate/60 transition-opacity">
          ·
        </span>
        <VersionTags
          tags={tags}
          containerClassName="max-w-[320px]"
          maxVisibleTags={5}
        />
      </>
    );
  };

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
                <span className="comet-body-s truncate">
                  {option?.label}{" "}
                  <span className="text-light-slate">
                    {option?.description}
                  </span>
                </span>
              );
            }}
            renderOption={(
              option: DropdownOption<string> & { tags?: string[] },
            ) => {
              return (
                <SelectItem
                  key={option.value}
                  value={option.value}
                  disabled={option.disabled}
                >
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <div className="flex min-w-0 items-center gap-1.5">
                      <span className="comet-body-s-accented shrink-0">
                        {option.label}
                      </span>
                      {renderTagsWithSeparator(option.tags)}
                    </div>
                    <span className="comet-body-s text-light-slate">
                      {option.description}
                    </span>
                  </div>
                </SelectItem>
              );
            }}
          ></SelectBox>
        </div>
      );
    } else {
      return (
        <div className="-mb-2 flex flex-col gap-0.5 px-0.5">
          <div className="flex min-w-0 items-center gap-1.5">
            <span className="comet-body-s-accented shrink-0">
              {version.commit}
            </span>
            {renderTagsWithSeparator(version.tags)}
          </div>
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
          {(imagesHaveChanges || videosHaveChanges || audiosHaveChanges) && (
            <>
              {imagesHaveChanges && (
                <div className="grid grid-cols-2 gap-4">
                  <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                    <MediaTagsList
                      type="image"
                      items={baseImages}
                      editable={false}
                      preview={true}
                    />
                  </div>
                  <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                    <MediaTagsList
                      type="image"
                      items={diffImages}
                      editable={false}
                      preview={true}
                    />
                  </div>
                </div>
              )}
              {videosHaveChanges && (
                <div className="grid grid-cols-2 gap-4">
                  <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                    <MediaTagsList
                      type="video"
                      items={baseVideos}
                      editable={false}
                      preview={true}
                    />
                  </div>
                  <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                    <MediaTagsList
                      type="video"
                      items={diffVideos}
                      editable={false}
                      preview={true}
                    />
                  </div>
                </div>
              )}
              {audiosHaveChanges && (
                <div className="grid grid-cols-2 gap-4">
                  <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                    <MediaTagsList
                      type="audio"
                      items={baseAudios}
                      editable={false}
                      preview={true}
                    />
                  </div>
                  <div className="flex min-w-0 flex-wrap items-center gap-2 overflow-hidden rounded-md border p-4">
                    <MediaTagsList
                      type="audio"
                      items={diffAudios}
                      editable={false}
                      preview={true}
                    />
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
};

export default ComparePromptVersionDialog;
