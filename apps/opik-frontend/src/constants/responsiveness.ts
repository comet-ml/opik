const PHONE_PORTRAIT_MAX_WIDTH = 767;
const PHONE_LANDSCAPE_MAX_HEIGHT = 480;

const QUERY_IS_TOUCH = "(pointer: coarse)";
export const QUERY_CAN_HOVER = "(hover: hover)";

export const QUERY_IS_PHONE_PORTRAIT = `
  ${QUERY_IS_TOUCH} and 
  (orientation: portrait) and 
  (max-width: ${PHONE_PORTRAIT_MAX_WIDTH}px)
`;

export const QUERY_IS_PHONE_LANDSCAPE = `
  ${QUERY_IS_TOUCH} and 
  (orientation: landscape) and 
  (max-height: ${PHONE_LANDSCAPE_MAX_HEIGHT}px)
`;
