import { DatasetItem } from "@/types/datasets";
import { PlaygroundPromptType } from "@/types/playground";
import { MessageContent } from "@/types/llm";
import { dropEmptySystemMessages } from "@/lib/playground";
import { MediaUrlCheckResult } from "@/api/playground/useMediaUrlsCheckMutation";
import { transformMessageIntoProviderMessage } from "@/v2/pages/PlaygroundPage/usePromptDatasetItemCombination";

export const MAX_CHECKED_MEDIA_URLS = 20;

const HTTP_URL_REGEX = /^https?:\/\//i;

const getMediaUrls = (content: MessageContent): string[] => {
  if (typeof content === "string") return [];

  return content.flatMap((part) => {
    if (part.type === "image_url") return [part.image_url.url];
    if (part.type === "video_url") return [part.video_url.url];
    if (part.type === "audio_url") return [part.audio_url.url];
    return [];
  });
};

export const collectRunMediaUrls = (
  prompts: PlaygroundPromptType[],
  datasetItems: DatasetItem[],
): string[] => {
  const rows =
    datasetItems.length > 0 ? datasetItems.map((item) => item.data) : [{}];
  const urls = new Set<string>();

  for (const row of rows) {
    for (const prompt of prompts) {
      let contents: MessageContent[];
      try {
        // all-or-nothing like the run: one message that can't be built means the row is never sent
        contents = dropEmptySystemMessages(prompt.messages).map(
          (message) =>
            transformMessageIntoProviderMessage(message, row).content,
        );
      } catch {
        continue;
      }

      for (const url of contents.flatMap(getMediaUrls)) {
        if (!HTTP_URL_REGEX.test(url)) continue;
        urls.add(url);
        if (urls.size === MAX_CHECKED_MEDIA_URLS) return [...urls];
      }
    }
  }

  return [...urls];
};

export const getFailedMediaUrls = (results: MediaUrlCheckResult[]) =>
  results.filter(({ status }) => status !== "ok" && status !== "skipped");

export const describeMediaUrlFailure = ({
  status,
  http_status,
  content_type,
}: MediaUrlCheckResult): string => {
  switch (status) {
    case "blocked":
      return "Blocked: it points to a private or internal address";
    case "unreachable":
      return "Could not connect to the host";
    case "timeout":
      return "The host did not answer in time";
    case "too_many_redirects":
      return "Too many redirects";
    case "http_error":
      return http_status
        ? `The host answered with HTTP ${http_status}`
        : "The host answered with an error";
    case "not_media":
      return content_type
        ? `Not an image, video or audio file (${content_type})`
        : "Not an image, video or audio file";
    case "invalid_url":
      return "Not a valid http or https URL";
    default:
      return "Could not be checked";
  }
};
