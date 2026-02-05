import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Image Attachment
 *
 * Demonstrates Image widget usage for displaying image attachments:
 * - L1 container with collapsible header
 * - Muted metadata row with bullet-separated values
 * - Preview thumbnail with expand/download actions
 * - Clickable tag linking to the image URL
 *
 * Figma reference: Node 364:20576
 */
export const imageAttachmentExample = {
  title: "Image Attachment",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Level1Container",
        props: { title: "Generated image", collapsible: true },
        children: ["metaRow", "image"],
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
        children: undefined,
        parentKey: "metaRow",
      },
      image: {
        id: "image",
        type: "Image",
        props: {
          src: { path: "/imageUrl" },
          alt: { path: "/altText" },
          label: "Image",
          tag: { path: "/imageTag" },
        },
        children: undefined,
        parentKey: "root",
      },
    },
    meta: {
      name: "Image Attachment",
      description: "Image widget with preview and metadata",
    },
  } satisfies ViewTree,
  sourceData: {
    metaLine: "Model: DALL-E 3 • Size: 1024×1024",
    imageUrl:
      "https://images.unsplash.com/photo-1506905925346-21bda4d32df4?w=400&h=400&fit=crop",
    altText: "Mountain landscape at sunset",
    imageTag: "https://example.com/plaza_mayor.png",
  } satisfies SourceData,
};
