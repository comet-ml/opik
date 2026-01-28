import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Video Attachment
 *
 * Demonstrates Video widget usage for displaying video attachments:
 * - Preview thumbnail with play button overlay
 * - Expand/download actions
 * - Native video controls on playback
 * - Label and optional tag
 *
 * Note: This is a pattern demo showing Video widget composition.
 */
export const videoAttachmentExample = {
  title: "Video Attachment",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Container",
        props: { layout: "stack", gap: "md", padding: "none" },
        children: ["header", "videoSection"],
        parentKey: null,
      },
      header: {
        id: "header",
        type: "Header",
        props: { text: "Video Attachment Example", level: 2 },
        children: undefined,
        parentKey: "root",
      },
      videoSection: {
        id: "videoSection",
        type: "Level1Container",
        props: { title: "Generated Video" },
        children: ["metaRow", "video"],
        parentKey: "root",
      },
      metaRow: {
        id: "metaRow",
        type: "InlineRow",
        props: {},
        children: [
          "modelLabel",
          "modelValue",
          "durationLabel",
          "durationValue",
        ],
        parentKey: "videoSection",
      },
      modelLabel: {
        id: "modelLabel",
        type: "Label",
        props: { text: "Model:" },
        children: undefined,
        parentKey: "metaRow",
      },
      modelValue: {
        id: "modelValue",
        type: "Text",
        props: { value: { path: "/model" }, variant: "body" },
        children: undefined,
        parentKey: "metaRow",
      },
      durationLabel: {
        id: "durationLabel",
        type: "Label",
        props: { text: "Duration:" },
        children: undefined,
        parentKey: "metaRow",
      },
      durationValue: {
        id: "durationValue",
        type: "Text",
        props: { value: { path: "/duration" }, variant: "body" },
        children: undefined,
        parentKey: "metaRow",
      },
      video: {
        id: "video",
        type: "Video",
        props: {
          src: { path: "/videoUrl" },
          label: { path: "/videoLabel" },
          tag: { path: "/videoTag" },
          controls: true,
        },
        children: undefined,
        parentKey: "videoSection",
      },
    },
    meta: {
      name: "Video Attachment",
      description: "Video widget with preview and playback controls",
    },
  } satisfies ViewTree,
  sourceData: {
    model: "Sora",
    duration: "15s",
    videoUrl:
      "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
    videoLabel: "Generated animation",
    videoTag: "720p",
  } satisfies SourceData,
};
