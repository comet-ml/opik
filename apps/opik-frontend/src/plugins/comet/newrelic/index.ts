import initSnippet from "./snippet";

export const initNewRelic = (licenseKey?: string, applicationID?: string) => {
  if (licenseKey && applicationID) {
    initSnippet(licenseKey, applicationID);
  }
};
