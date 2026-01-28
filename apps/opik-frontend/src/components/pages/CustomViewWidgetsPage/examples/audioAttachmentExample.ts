import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Audio Attachment
 *
 * Demonstrates Audio widget usage for displaying audio attachments:
 * - Native audio controls with play/pause/seek
 * - Expand/download actions
 * - Label and optional tag
 *
 * Note: This is a pattern demo showing Audio widget composition.
 */
export const audioAttachmentExample = {
  title: "Audio Attachment",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Container",
        props: { layout: "stack", gap: "md", padding: "none" },
        children: ["header", "audioSection"],
        parentKey: null,
      },
      header: {
        id: "header",
        type: "Header",
        props: { text: "Audio Attachment Example", level: 2 },
        children: undefined,
        parentKey: "root",
      },
      audioSection: {
        id: "audioSection",
        type: "Level1Container",
        props: { title: "Generated Audio" },
        children: ["metaRow", "audio"],
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
        parentKey: "audioSection",
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
      audio: {
        id: "audio",
        type: "Audio",
        props: {
          src: { path: "/audioUrl" },
          label: { path: "/audioLabel" },
          tag: { path: "/audioTag" },
          controls: true,
        },
        children: undefined,
        parentKey: "audioSection",
      },
    },
    meta: {
      name: "Audio Attachment",
      description: "Audio widget with native playback controls",
    },
  } satisfies ViewTree,
  sourceData: {
    model: "TTS-1-HD",
    duration: "12s",
    audioUrl: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
    audioLabel: "Generated speech",
    audioTag: "MP3",
  } satisfies SourceData,
};
