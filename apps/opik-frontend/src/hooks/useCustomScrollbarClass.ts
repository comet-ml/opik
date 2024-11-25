export default function useCustomScrollbarClass() {
  const userAgent = window.navigator.userAgent.toLowerCase();

  if (/(win32|win64|windows|wince)/i.test(userAgent)) {
    document.body.classList.add("comet-custom-scrollbar");
    if (userAgent.includes("firefox")) {
      document.body.classList.add("firefox");
    }
  }
}
