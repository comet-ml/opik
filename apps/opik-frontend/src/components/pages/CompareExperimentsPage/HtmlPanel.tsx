import React, { useEffect, useRef, useMemo, useCallback } from "react";
import { HtmlPanelConfig } from "./dashboardTypes";

interface HtmlPanelProps {
  config: HtmlPanelConfig;
  width?: number;
  height?: number;
}

const HtmlPanel: React.FC<HtmlPanelProps> = ({ config, width = 400, height = 300 }) => {
  const iframeRef = useRef<HTMLIFrameElement>(null);

  // Memoize the CSS links string
  const cssLinks = useMemo(() => {
    return config.cssIncludes.map(url => 
      `<link rel="stylesheet" href="${url}" crossorigin="anonymous">`
    ).join('\n');
  }, [config.cssIncludes]);

  // Memoize the JS scripts string
  const jsScripts = useMemo(() => {
    return config.jsIncludes.map(url => 
      `<script src="${url}" crossorigin="anonymous"></script>`
    ).join('\n');
  }, [config.jsIncludes]);

  // Memoize the complete HTML document
  const htmlDocument = useMemo(() => {
    return `
<!DOCTYPE html>
<html>
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>HTML Panel</title>
  <style>
    body {
      margin: 0;
      padding: 16px;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Roboto', sans-serif;
      background: white;
      overflow: auto;
    }
    .html-panel-container {
      width: 100%;
      height: 100%;
    }
    /* Common chart libraries styling */
    .chart-container {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
    }
  </style>
  ${cssLinks}
</head>
<body>
  <div class="html-panel-container">
    ${config.htmlContent}
  </div>
  ${config.allowScripts ? jsScripts : ''}
  ${config.allowScripts ? `
  <script>
    // Auto-resize iframe to content
    function resizeIframe() {
      const height = Math.max(
        document.body.scrollHeight,
        document.body.offsetHeight,
        document.documentElement.clientHeight,
        document.documentElement.scrollHeight,
        document.documentElement.offsetHeight
      );
      window.parent.postMessage({ type: 'resize', height: height }, '*');
    }
    
    // Resize on load and when content changes
    window.addEventListener('load', resizeIframe);
    window.addEventListener('resize', resizeIframe);
    
    // Resize after a short delay to allow for dynamic content
    setTimeout(resizeIframe, 100);
    setTimeout(resizeIframe, 500);
    setTimeout(resizeIframe, 1000);
  </script>
  ` : ''}
</body>
</html>`;
  }, [config.htmlContent, config.allowScripts, cssLinks, jsScripts]);

  // Memoize iframe sandbox attributes
  const sandboxAttributes = useMemo(() => {
    return config.allowScripts ? 
      "allow-scripts allow-same-origin allow-forms allow-popups allow-modals" : 
      "allow-same-origin";
  }, [config.allowScripts]);

  // Memoize iframe style
  const iframeStyle = useMemo(() => ({
    width: '100%',
    height: `${config.height || height}px`,
    border: 'none',
    borderRadius: '6px',
  }), [config.height, height]);

  // Handle iframe resize messages
  const handleMessage = useCallback((event: MessageEvent) => {
    if (event.data.type === 'resize' && iframeRef.current) {
      const newHeight = Math.min(event.data.height + 20, config.height || 400);
      iframeRef.current.style.height = `${newHeight}px`;
    }
  }, [config.height]);

  useEffect(() => {
    if (!iframeRef.current) return;

    const iframe = iframeRef.current;
    const iframeDoc = iframe.contentDocument || iframe.contentWindow?.document;
    
    if (!iframeDoc) return;

    // Write the HTML document to the iframe
    iframeDoc.open();
    iframeDoc.write(htmlDocument);
    iframeDoc.close();

  }, [htmlDocument]);

  useEffect(() => {
    window.addEventListener('message', handleMessage);
    return () => window.removeEventListener('message', handleMessage);
  }, [handleMessage]);

  return (
    <div className="html-panel-wrapper bg-background border rounded-md">
      <iframe
        ref={iframeRef}
        style={iframeStyle}
        sandbox={sandboxAttributes}
        title="HTML Panel Content"
      />
    </div>
  );
};

export default React.memo(HtmlPanel); 
