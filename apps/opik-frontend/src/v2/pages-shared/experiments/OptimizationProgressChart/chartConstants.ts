/**
 * Constants for the optimization progress chart layout and sizing.
 */

// Dot radii (regular dot 6px diameter, best dot 8px diameter)
export const DOT_RADIUS_DEFAULT = 3;
export const DOT_RADIUS_BEST = 4;
export const SELECTION_RING_EXTRA_RADIUS = 4;
export const SELECTION_RING_STROKE_WIDTH = 2;
export const SELECTION_RING_STROKE_OPACITY = 0.4;

// Hover growth applied on top of the base radius
export const DOT_RADIUS_HOVER_GROWTH = 1;

// Max cursor→dot distance (px) matched by the nearest-dot hover/click handler.
// Dots are resolved by proximity on the chart container instead of per-dot hit
// areas, so overlapping clustered dots can no longer fight over the pointer
// (which flickered the hover and made dense fan-outs impossible to target).
export const HOVER_HIT_DISTANCE = 22;

/** Resolve a dot's radius from its best/hover state. */
export const getDotRadius = ({
  isBest = false,
  isHovered = false,
}: {
  isBest?: boolean;
  isHovered?: boolean;
}): number =>
  (isBest ? DOT_RADIUS_BEST : DOT_RADIUS_DEFAULT) +
  (isHovered ? DOT_RADIUS_HOVER_GROWTH : 0);

// Dot styling. The colored best-dot ring width; other dots draw no stroke.
export const DOT_STROKE_WIDTH = 1.5;
export const DOT_BEST_RING_WIDTH = 2;

// Overlap spacing for dots sharing the same (step, score)
export const OVERLAP_SPACING = 16;

// "Best trial" badge above the best dot: a fuchsia-300 pill with dark text and
// a downward tail pointing at the dot.
export const BEST_LABEL_WIDTH = 64;
export const BEST_LABEL_HEIGHT = 20;
export const BEST_LABEL_BORDER_RADIUS = 6;
export const BEST_LABEL_FONT_SIZE = 12;
export const BEST_LABEL_TAIL_WIDTH = 10;
export const BEST_LABEL_TAIL_HEIGHT = 6;
// Gap between the tail tip and the top of the dot.
export const BEST_LABEL_GAP = 4;

// Edge styling — the progress trend line (solid fuchsia-500)
export const EDGE_STROKE_WIDTH = 2;
export const EDGE_STROKE_OPACITY = 1;
export const EDGE_STROKE_COLOR = "var(--color-fuchsia)";

// Dot border colour (1.5px white/background border so dots read crisply over
// the trend line and grid). Paired with DOT_STROKE_WIDTH.
export const DOT_STROKE_COLOR = "hsl(var(--background))";

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
// top leaves room for the "Best trial" badge above a best dot sitting at y=1
// (grown radius 5 + gap 4 + tail 6 + label 20 = 35px) so it never clips.
export const CHART_MARGIN = { top: 40, bottom: 10, left: 10, right: 10 };
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
