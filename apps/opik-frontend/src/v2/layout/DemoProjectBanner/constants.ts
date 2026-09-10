/**
 * The demo banner's height, in px, known statically from its own styling.
 *
 * The layout offsets its content by the summed banner height. Waiting for a
 * measurement leaves that sum at 0 for as long as the measurement takes, and
 * the bar then overlaps the content it should have pushed down — which is what
 * happened once this banner's visibility started resolving from a query
 * instead of from synchronous state.
 */
export const DEMO_BANNER_HEIGHT = 32;

/**
 * The Tailwind class that must render `DEMO_BANNER_HEIGHT`. Kept beside it so
 * the pair is edited together; a test asserts they agree.
 */
export const DEMO_BANNER_HEIGHT_CLASS = "h-8";
