// Classic (space-taking) scrollbars: macOS with a mouse connected or "Show scroll bars:
// Always", and Linux. The probe is attached to <html>, outside the `.dark body` scrollbar
// rules in main.scss, so it measures the native width.
const hasClassicScrollbars = () => {
  const probe = document.createElement("div");
  probe.style.cssText =
    "position:absolute;top:-9999px;width:100px;height:100px;overflow:scroll";
  document.documentElement.appendChild(probe);
  const width = probe.offsetWidth - probe.clientWidth;
  probe.remove();
  return width > 0;
};

export default function useCustomScrollbarClass() {
  const userAgent = window.navigator.userAgent.toLowerCase();

  if (/(win32|win64|windows|wince)/i.test(userAgent)) {
    document.body.classList.add("comet-custom-scrollbar");
    if (userAgent.includes("firefox")) {
      document.body.classList.add("firefox");
    }
  } else {
    if (hasClassicScrollbars()) {
      document.body.classList.add("comet-classic-scrollbars");
    }
    if (
      /^((?!chrome|chromium|crios|fxios|edg|android).)*safari/.test(userAgent)
    ) {
      document.body.classList.add("safari");
    }
  }
}
