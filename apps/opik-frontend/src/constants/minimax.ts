export const MINIMAX_TEXT_MODEL_IDS = [
  "minimax/minimax-m3.1-flash:free",
  "minimax/minimax-m3",
];

export const isMiniMaxTextModel = (model: string): boolean =>
  /^minimax\/minimax-(?:m\d|01(?:$|:))/i.test(model);
