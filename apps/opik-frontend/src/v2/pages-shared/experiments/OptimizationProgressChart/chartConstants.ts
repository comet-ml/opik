/**
 * Constants for the optimization progress chart layout and sizing.
 */

// Dot radii
export const DOT_RADIUS_DEFAULT = 6;
export const DOT_RADIUS_BEST = 8;
export const SELECTION_RING_EXTRA_RADIUS = 4;
export const SELECTION_RING_STROKE_WIDTH = 2;
export const SELECTION_RING_STROKE_OPACITY = 0.4;

// Dot styling
export const DOT_STROKE_WIDTH = 1.5;
export const DOT_STROKE_COLOR = "white";

// Overlap spacing for dots sharing the same (step, score)
export const OVERLAP_SPACING = 16;

// "Best candidate" label dimensions
export const BEST_LABEL_WIDTH = 92;
export const BEST_LABEL_HEIGHT = 18;
export const BEST_LABEL_BORDER_RADIUS = 4;
export const BEST_LABEL_Y_OFFSET = 22;
export const BEST_LABEL_TEXT_Y_OFFSET = 10;
export const BEST_LABEL_FONT_SIZE = 11;
export const BEST_LABEL_OPACITY = 0.85;

// Edge styling
export const EDGE_STROKE_WIDTH = 1;
export const EDGE_STROKE_OPACITY = 0.4;
export const EDGE_STROKE_COLOR = "hsl(var(--muted-foreground))";

// Ghost candidate edge styling
export const GHOST_EDGE_STROKE_WIDTH = 1.5;
export const GHOST_EDGE_DASH_ARRAY = "4 3";
export const GHOST_EDGE_STROKE_OPACITY = 0.5;
export const GHOST_EDGE_ANIMATION_FROM = "14";
export const GHOST_EDGE_ANIMATION_DUR = "1s";

// Ghost candidate dot styling
export const GHOST_DOT_FILL_OPACITY = 0.2;
export const GHOST_DOT_STROKE_OPACITY = 0.4;

// Chart margins
export const CHART_MARGIN = { top: 30, bottom: 10, left: 10, right: 10 };
export const X_AXIS_PADDING = { left: 20 };
export const X_DOMAIN_EXTRA = 0.3;

// Tooltip offset
export const TOOLTIP_Y_OFFSET = 16;

// Best-candidate pulsing animation
export const BEST_PULSE_DUR = "3s";

// Ghost-candidate breathing animation
export const GHOST_BREATHE_DUR = "2s";

// Shared trial click handler: routes to onTrialClick (navigation) or onTrialSelect (selection)
export const createTrialClickHandler = (
  candidateId: string,
  onTrialClick?: (id: string) => void,
  onTrialSelect?: (id: string) => void,
) => {
  return () => {
    if (onTrialClick) {
      onTrialClick(candidateId);
    } else {
      onTrialSelect?.(candidateId);
    }
  };
};
