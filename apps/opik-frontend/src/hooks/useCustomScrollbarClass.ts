export default function useCustomScrollbarClass() {
  const userAgent = window.navigator.userAgent.toLowerCase();

  if (/(win32|win64|windows|wince)/i.test(userAgent)) {
    return `comet-custom-scrollbar ${
      userAgent.includes("firefox") ? "firefox" : ""
    }`;
  }

  return "";
}
