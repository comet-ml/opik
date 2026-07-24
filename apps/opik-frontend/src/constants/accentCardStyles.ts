// Shared accent palette for the tinted "insight" cards used in two places:
// the optimization-studio template cards and the daily-briefing action items.
// The layouts differ (horizontal w/ CTA vs. vertical), so only the color
// treatment is shared here — each consumer keeps its own structure.
//
// `card` is the tinted surface + border and its hover state (matches the Figma
// hover frame); `iconBg` is the colored icon-chip background. Consumers supply
// their own layout, padding, shadow and `transition-colors`.
export type AccentCardStyle = {
  card: string;
  iconBg: string;
};

export const ACCENT_CARD_STYLES: AccentCardStyle[] = [
  {
    card: "border-sky-200/60 bg-sky-200/10 hover:bg-sky-200/20 hover:border-sky-200/80",
    iconBg: "bg-[#89deff]",
  },
  {
    card: "border-violet-300/40 bg-violet-300/10 hover:bg-violet-300/20 hover:border-violet-300/60",
    iconBg: "bg-[#a78bfa]",
  },
  {
    card: "border-fuchsia-300/50 bg-fuchsia-300/10 hover:bg-fuchsia-300/20 hover:border-fuchsia-300/70",
    iconBg: "bg-[#e25af6]",
  },
];
