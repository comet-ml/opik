import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Audio Attachment
 *
 * Demonstrates Audio widget usage for displaying audio attachments:
 * - L1 container with collapsible header
 * - Muted metadata row with bullet-separated values
 * - Custom styled audio controls with play/pause/seek
 * - Clickable tag linking to the audio URL
 *
 * Figma reference: Node 364:20708
 */
export const audioAttachmentExample = {
  title: "Audio Attachment",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Level1Container",
        props: { title: "Generated audio", collapsible: true },
        children: ["metaRow", "audio"],
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
      audio: {
        id: "audio",
        type: "Audio",
        props: {
          src: { path: "/audioUrl" },
          label: "Generated speech",
          tag: { path: "/audioTag" },
        },
        children: null,
        parentKey: "root",
      },
    },
    meta: {
      name: "Audio Attachment",
      description: "Audio widget with custom playback controls",
    },
  } satisfies ViewTree,
  sourceData: {
    metaLine: "Model: TTS-1-HD • Size: 12s",
    audioUrl: "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
    audioTag: "voice_note_01.mp3",
  } satisfies SourceData,
};
