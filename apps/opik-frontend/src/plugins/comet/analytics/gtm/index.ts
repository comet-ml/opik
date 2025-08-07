import initGTMSnippet from "./snippet";

export const initGTM = (OPIK_ANALYTICS_ID?: string) => {
  if (OPIK_ANALYTICS_ID) {
    initGTMSnippet(OPIK_ANALYTICS_ID);
  }
};
