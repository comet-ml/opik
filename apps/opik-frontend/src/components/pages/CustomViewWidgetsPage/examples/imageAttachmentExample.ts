import type { ViewTree, SourceData } from "@/lib/data-view/core/types";

/**
 * PATTERN DEMO: Image Attachment
 *
 * Demonstrates Image widget usage for displaying image attachments:
 * - Preview thumbnail with expand/download actions
 * - Label and optional tag
 * - Support for URLs and data URIs
 *
 * Note: This is a pattern demo showing Image widget composition.
 */
export const imageAttachmentExample = {
  title: "Image Attachment",
  tree: {
    version: 1,
    root: "root",
    nodes: {
      root: {
        id: "root",
        type: "Container",
        props: { layout: "stack", gap: "md", padding: "none" },
        children: ["header", "imageSection"],
        parentKey: null,
      },
      header: {
        id: "header",
        type: "Header",
        props: { text: "Image Attachment Example", level: 2 },
        children: undefined,
        parentKey: "root",
      },
      imageSection: {
        id: "imageSection",
        type: "Level1Container",
        props: { title: "Generated Image" },
        children: ["metaRow", "image"],
        parentKey: "root",
      },
      metaRow: {
        id: "metaRow",
        type: "InlineRow",
        props: {},
        children: ["modelLabel", "modelValue", "sizeLabel", "sizeValue"],
        parentKey: "imageSection",
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
      sizeLabel: {
        id: "sizeLabel",
        type: "Label",
        props: { text: "Size:" },
        children: undefined,
        parentKey: "metaRow",
      },
      sizeValue: {
        id: "sizeValue",
        type: "Text",
        props: { value: { path: "/size" }, variant: "body" },
        children: undefined,
        parentKey: "metaRow",
      },
      image: {
        id: "image",
        type: "Image",
        props: {
          src: { path: "/imageUrl" },
          alt: { path: "/altText" },
          label: { path: "/imageLabel" },
          tag: { path: "/imageTag" },
        },
        children: undefined,
        parentKey: "imageSection",
      },
    },
    meta: {
      name: "Image Attachment",
      description: "Image widget with preview and metadata",
    },
  } satisfies ViewTree,
  sourceData: {
    model: "DALL-E 3",
    size: "1024x1024",
    imageUrl:
      "https://images.unsplash.com/photo-1506905925346-21bda4d32df4?w=400&h=400&fit=crop",
    altText: "Mountain landscape at sunset",
    imageLabel: "Generated landscape",
    imageTag: "1024x1024",
  } satisfies SourceData,
};
