import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Video Attachment
 *
 * Demonstrates Video widget usage for displaying video attachments:
 * - L1 container with collapsible header
 * - Muted metadata row with bullet-separated values
 * - Preview thumbnail with play button overlay
 * - Clickable tag linking to the video URL
 *
 * Figma reference: Node 364:20764
 */
export const videoAttachmentExample = {
  title: "Video Attachment",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Level1Container",
        props: { title: "Generated video", collapsible: true },
        children: ["metaRow", "video"],
        parentKey: null,
      },
      metaRow: {
        id: "metaRow",
        type: "InlineRow",
        props: { background: "muted" },
        children: ["metaText"],
        parentKey: "root",
      },
      metaText: {
        id: "metaText",
        type: "Text",
        props: { value: { path: "/metaLine" } },
        children: null,
        parentKey: "metaRow",
      },
      video: {
        id: "video",
        type: "Video",
        props: {
          src: { path: "/videoUrl" },
          label: "Video",
          tag: { path: "/videoTag" },
        },
        children: null,
        parentKey: "root",
      },
    },
    meta: {
      name: "Video Attachment",
      description: "Video widget with preview and playback controls",
    },
  } satisfies ViewTree,
  sourceData: {
    metaLine: "Model: Sora • Duration: 15s",
    videoUrl:
      "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
    videoTag:
      "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
  } satisfies SourceData,
};
