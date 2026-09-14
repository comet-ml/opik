// Shared by both tiles: the chip chrome from the design. Co-located rather than
// promoted to lib/ — it means nothing outside this card. Sized to its content,
// so the row wraps on the labels rather than stretching them.
export const MCP_TILE_CLASS =
  "flex h-6 w-fit shrink-0 items-center gap-1.5 rounded border border-border bg-background px-2 font-mono text-xs text-foreground transition-colors hover:bg-primary-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring";
