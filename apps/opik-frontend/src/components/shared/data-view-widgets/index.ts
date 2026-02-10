// ============================================================================
// DATA VIEW WIDGETS
// ============================================================================
// Central export for all custom view widgets.
// Widgets are organized by category:
// - primitives/: Inline display widgets (Text, Number, Label, Tag, Bool, Link, etc.)
// - blocks/: Block-level widgets (Header, Divider, TextBlock, Code, Image, Video, Audio, File, ChatMessage)
// - containers/: Layout widgets (Level1Container, Level2Container, InlineRow)

import { createCatalog, type ComponentRegistry } from "@/lib/data-view";

// Re-export primitives
export * from "./primitives";
export { inlineWidgetConfigs, inlineWidgetRegistry } from "./primitives";

// Re-export blocks
export * from "./blocks";
export { blockWidgetConfigs, blockWidgetRegistry } from "./blocks";

// Re-export containers
export * from "./containers";
export { containerWidgetConfigs, containerWidgetRegistry } from "./containers";

// ============================================================================
// COMBINED CATALOG (for AI generation)
// ============================================================================

import { inlineWidgetConfigs } from "./primitives";
import { blockWidgetConfigs } from "./blocks";
import { containerWidgetConfigs } from "./containers";

/**
 * Combined catalog of all available widgets.
 * Used with generatePrompt() for AI view generation.
 */
export const customViewCatalog = createCatalog({
  name: "CustomView",
  components: {
    // Inline primitives
    Text: {
      props: inlineWidgetConfigs.Text.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.Text.description,
    },
    Number: {
      props: inlineWidgetConfigs.Number.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.Number.description,
    },
    Label: {
      props: inlineWidgetConfigs.Label.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.Label.description,
    },
    Tag: {
      props: inlineWidgetConfigs.Tag.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.Tag.description,
    },
    Bool: {
      props: inlineWidgetConfigs.Bool.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.Bool.description,
    },
    BoolChip: {
      props: inlineWidgetConfigs.BoolChip.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.BoolChip.description,
    },
    LinkButton: {
      props: inlineWidgetConfigs.LinkButton.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.LinkButton.description,
    },
    Link: {
      props: inlineWidgetConfigs.Link.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.Link.description,
    },
    TraceLink: {
      props: inlineWidgetConfigs.TraceLink.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.TraceLink.description,
    },
    ThreadLink: {
      props: inlineWidgetConfigs.ThreadLink.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.ThreadLink.description,
    },
    StatsRow: {
      props: inlineWidgetConfigs.StatsRow.schema,
      hasChildren: false,
      description: inlineWidgetConfigs.StatsRow.description,
    },
    // Block primitives
    Header: {
      props: blockWidgetConfigs.Header.schema,
      hasChildren: false,
      description: blockWidgetConfigs.Header.description,
    },
    Divider: {
      props: blockWidgetConfigs.Divider.schema,
      hasChildren: false,
      description: blockWidgetConfigs.Divider.description,
    },
    TextBlock: {
      props: blockWidgetConfigs.TextBlock.schema,
      hasChildren: false,
      description: blockWidgetConfigs.TextBlock.description,
    },
    Code: {
      props: blockWidgetConfigs.Code.schema,
      hasChildren: false,
      description: blockWidgetConfigs.Code.description,
    },
    // Container widgets
    Container: {
      props: containerWidgetConfigs.Container.schema,
      hasChildren: true,
      description: containerWidgetConfigs.Container.description,
    },
    Level1Container: {
      props: containerWidgetConfigs.Level1Container.schema,
      hasChildren: true,
      description: containerWidgetConfigs.Level1Container.description,
    },
    Level2Container: {
      props: containerWidgetConfigs.Level2Container.schema,
      hasChildren: true,
      description: containerWidgetConfigs.Level2Container.description,
    },
    InlineRow: {
      props: containerWidgetConfigs.InlineRow.schema,
      hasChildren: true,
      description: containerWidgetConfigs.InlineRow.description,
    },
    // Media widgets
    Image: {
      props: blockWidgetConfigs.Image.schema,
      hasChildren: false,
      description: blockWidgetConfigs.Image.description,
    },
    Video: {
      props: blockWidgetConfigs.Video.schema,
      hasChildren: false,
      description: blockWidgetConfigs.Video.description,
    },
    Audio: {
      props: blockWidgetConfigs.Audio.schema,
      hasChildren: false,
      description: blockWidgetConfigs.Audio.description,
    },
    File: {
      props: blockWidgetConfigs.File.schema,
      hasChildren: false,
      description: blockWidgetConfigs.File.description,
    },
    ChatMessage: {
      props: blockWidgetConfigs.ChatMessage.schema,
      hasChildren: false,
      description: blockWidgetConfigs.ChatMessage.description,
    },
  },
});

// ============================================================================
// COMBINED REGISTRY (for rendering)
// ============================================================================

import { inlineWidgetRegistry } from "./primitives";
import { blockWidgetRegistry } from "./blocks";
import { containerWidgetRegistry } from "./containers";

/**
 * Combined registry of all widget renderers.
 * Pass this to the Renderer component.
 */
export const customViewRegistry: ComponentRegistry = {
  ...inlineWidgetRegistry,
  ...blockWidgetRegistry,
  ...containerWidgetRegistry,
};
