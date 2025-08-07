import initGTMSnippet from "./snippet";

export const initGTM = (OPIK_GTM_ID?: string) => {
  if (OPIK_GTM_ID) {
    initGTMSnippet(OPIK_GTM_ID);
  }
};
