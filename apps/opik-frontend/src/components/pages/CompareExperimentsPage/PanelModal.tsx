import React, { useState, useEffect, useRef, useMemo, useCallback } from "react";
import Editor from "@monaco-editor/react";
import { Panel, PythonPanel, ChartPanel, TextPanel, MetricPanel, HtmlPanel } from "./dashboardTypes";
import PythonPanelComponent from "./PythonPanel";
import ChartPanelComponent from "./ChartPanel";
import TextPanelComponent from "./TextPanel";
import MetricPanelComponent from "./MetricPanel";
import HtmlPanelComponent from "./HtmlPanel";
import { Button } from "@/components/ui/button";

interface PanelModalProps {
  open: boolean;
  onClose: () => void;
  onSave: (panel: Panel) => void;
  sectionId: string;
  panel?: Panel; // For editing existing panels
}

const DEFAULT_PYTHON_CODE = `# Python Panel Example
import matplotlib.pyplot as plt
import numpy as np

# Generate sample data
x = np.linspace(0, 10, 100)
y = np.sin(x)

# Create plot
plt.figure(figsize=(10, 6))
plt.plot(x, y, 'b-', linewidth=2, label='sin(x)')
plt.xlabel('X values')
plt.ylabel('Y values')
plt.title('Sample Python Panel')
plt.legend()
plt.grid(True, alpha=0.3)
plt.show()

print("Python panel executed successfully!")`;

const DEFAULT_TEXT_CONTENT = `# Welcome to Text Panel

This is a **markdown** text panel. You can use:

- **Bold** and *italic* text
- Lists and bullet points
- [Links](https://example.com)
- Code blocks

\`\`\`python
print("Hello World")
\`\`\`

## Features
- Rich text formatting
- Markdown support
- HTML rendering`;

const DEFAULT_HTML_CONTENT = `<div class="chart-container">
  <h2>Interactive Chart Example</h2>
  <div id="chart" style="width: 100%; height: 300px;"></div>
</div>

<script>
// Example using Chart.js (add Chart.js to external libraries)
const ctx = document.getElementById('chart');
if (ctx && typeof Chart !== 'undefined') {
  new Chart(ctx, {
    type: 'line',
    data: {
      labels: ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun'],
      datasets: [{
        label: 'Sample Data',
        data: [12, 19, 3, 5, 2, 3],
        borderColor: 'rgb(75, 192, 192)',
        backgroundColor: 'rgba(75, 192, 192, 0.2)',
        tension: 0.1
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false
    }
  });
} else {
  document.getElementById('chart').innerHTML = '<p>Chart.js not loaded. Add it to external libraries.</p>';
}
</script>`;

const PanelModal: React.FC<PanelModalProps> = ({ open, onClose, onSave, sectionId, panel }) => {
  const [name, setName] = useState("New Panel");
  const [panelType, setPanelType] = useState<"python" | "chart" | "text" | "metric" | "html">("python");
  
  // Resizable splitter state
  const [configWidth, setConfigWidth] = useState(60); // percentage
  const [isDragging, setIsDragging] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  
  // Python panel state
  const [pythonCode, setPythonCode] = useState(DEFAULT_PYTHON_CODE);
  
  // Chart panel state
  const [chartType, setChartType] = useState<"line" | "bar" | "scatter" | "histogram">("line");
  const [dataSource, setDataSource] = useState("");
  const [xAxis, setXAxis] = useState("");
  const [yAxis, setYAxis] = useState("");
  const [chartTitle, setChartTitle] = useState("");
  
  // Text panel state
  const [textContent, setTextContent] = useState(DEFAULT_TEXT_CONTENT);
  const [textFormat, setTextFormat] = useState<"markdown" | "html" | "plain">("markdown");
  
  // Metric panel state
  const [metricName, setMetricName] = useState("");
  const [aggregation, setAggregation] = useState<"sum" | "avg" | "count" | "min" | "max">("avg");
  const [timeRange, setTimeRange] = useState("24h");
  const [displayFormat, setDisplayFormat] = useState<"number" | "percentage" | "currency">("number");

  // HTML panel state
  const [htmlContent, setHtmlContent] = useState(DEFAULT_HTML_CONTENT);
  const [allowScripts, setAllowScripts] = useState(true);
  const [htmlHeight, setHtmlHeight] = useState(400);
  const [cssIncludes, setCssIncludes] = useState<string[]>([]);
  const [jsIncludes, setJsIncludes] = useState<string[]>([]);
  const [newCssUrl, setNewCssUrl] = useState("");
  const [newJsUrl, setNewJsUrl] = useState("");

  const isEditing = useMemo(() => !!panel, [panel]);

  // Memoized panel type options
  const panelTypes = useMemo(() => [
    { value: "python", label: "Python Panel", icon: "🐍" },
    { value: "chart", label: "Chart Panel", icon: "📊" },
    { value: "text", label: "Text Panel", icon: "📝" },
    { value: "metric", label: "Metric Panel", icon: "📈" },
    { value: "html", label: "HTML Panel", icon: "🌐" },
  ], []);

  // Initialize form with panel data when editing
  useEffect(() => {
    if (panel) {
      setName(panel.name);
      setPanelType(panel.data.type);
      
      switch (panel.data.type) {
        case "python":
          setPythonCode(panel.data.config.code);
          break;
        case "chart":
          setChartType(panel.data.config.chartType);
          setDataSource(panel.data.config.dataSource);
          setXAxis(panel.data.config.xAxis);
          setYAxis(panel.data.config.yAxis);
          setChartTitle(panel.data.config.title || "");
          break;
        case "text":
          setTextContent(panel.data.config.content);
          setTextFormat(panel.data.config.format);
          break;
        case "metric":
          setMetricName(panel.data.config.metricName);
          setAggregation(panel.data.config.aggregation);
          setTimeRange(panel.data.config.timeRange);
          setDisplayFormat(panel.data.config.displayFormat);
          break;
        case "html":
          setHtmlContent(panel.data.config.htmlContent);
          setAllowScripts(panel.data.config.allowScripts);
          setHtmlHeight(panel.data.config.height);
          setCssIncludes(panel.data.config.cssIncludes || []);
          setJsIncludes(panel.data.config.jsIncludes || []);
          break;
      }
    } else {
      // Reset to defaults for new panel
      setName("New Panel");
      setPanelType("python");
      setPythonCode(DEFAULT_PYTHON_CODE);
      setChartType("line");
      setDataSource("");
      setXAxis("");
      setYAxis("");
      setChartTitle("");
      setTextContent(DEFAULT_TEXT_CONTENT);
      setTextFormat("markdown");
      setMetricName("");
      setAggregation("avg");
      setTimeRange("24h");
      setDisplayFormat("number");
      setHtmlContent(DEFAULT_HTML_CONTENT);
      setAllowScripts(true);
      setHtmlHeight(400);
      setCssIncludes([]);
      setJsIncludes([]);
    }
  }, [panel]);

  // Memoized handlers
  const handleSave = useCallback(() => {
    let panelData: Panel;
    
    switch (panelType) {
      case "python":
        panelData = {
          id: panel?.id || `panel-${Date.now()}`,
          name,
          data: {
            type: 'python',
            config: { code: pythonCode },
          } as PythonPanel,
        };
        break;
      case "chart":
        panelData = {
          id: panel?.id || `panel-${Date.now()}`,
          name,
          data: {
            type: 'chart',
            config: { 
              chartType, 
              dataSource, 
              xAxis, 
              yAxis, 
              title: chartTitle || undefined 
            },
          } as ChartPanel,
        };
        break;
      case "text":
        panelData = {
          id: panel?.id || `panel-${Date.now()}`,
          name,
          data: {
            type: 'text',
            config: { content: textContent, format: textFormat },
          } as TextPanel,
        };
        break;
      case "metric":
        panelData = {
          id: panel?.id || `panel-${Date.now()}`,
          name,
          data: {
            type: 'metric',
            config: { 
              metricName, 
              aggregation, 
              timeRange, 
              displayFormat 
            },
          } as MetricPanel,
        };
        break;
      case "html":
        panelData = {
          id: panel?.id || `panel-${Date.now()}`,
          name,
          data: {
            type: 'html',
            config: { 
              htmlContent, 
              allowScripts, 
              height: htmlHeight,
              cssIncludes,
              jsIncludes
            },
          } as HtmlPanel,
        };
        break;
    }
    
    onSave(panelData);
    onClose();
  }, [
    panelType, 
    panel?.id, 
    name, 
    pythonCode, 
    chartType, 
    dataSource, 
    xAxis, 
    yAxis, 
    chartTitle, 
    textContent, 
    textFormat, 
    metricName, 
    aggregation, 
    timeRange, 
    displayFormat, 
    htmlContent, 
    allowScripts, 
    htmlHeight, 
    cssIncludes, 
    jsIncludes, 
    onSave, 
    onClose
  ]);

  const addCssLibrary = useCallback(() => {
    if (newCssUrl.trim() && !cssIncludes.includes(newCssUrl.trim())) {
      setCssIncludes([...cssIncludes, newCssUrl.trim()]);
      setNewCssUrl("");
    }
  }, [newCssUrl, cssIncludes]);

  const addJsLibrary = useCallback(() => {
    if (newJsUrl.trim() && !jsIncludes.includes(newJsUrl.trim())) {
      setJsIncludes([...jsIncludes, newJsUrl.trim()]);
      setNewJsUrl("");
    }
  }, [newJsUrl, jsIncludes]);

  const removeCssLibrary = useCallback((url: string) => {
    setCssIncludes(cssIncludes.filter(css => css !== url));
  }, [cssIncludes]);

  const removeJsLibrary = useCallback((url: string) => {
    setJsIncludes(jsIncludes.filter(js => js !== url));
  }, [jsIncludes]);

  // Handle resizable splitter
  const handleMouseDown = useCallback((e: React.MouseEvent) => {
    e.preventDefault();
    setIsDragging(true);
  }, []);

  useEffect(() => {
    const handleMouseMove = (e: MouseEvent) => {
      if (!containerRef.current) return;
      
      const container = containerRef.current;
      const rect = container.getBoundingClientRect();
      const sidebarWidth = 256; // 16rem = 256px
      const availableWidth = rect.width - sidebarWidth;
      const mouseX = e.clientX - rect.left - sidebarWidth;
      const newConfigWidth = Math.min(Math.max((mouseX / availableWidth) * 100, 30), 80);
      
      setConfigWidth(newConfigWidth);
    };

    const handleMouseUp = () => {
      setIsDragging(false);
    };

    if (isDragging) {
      document.addEventListener('mousemove', handleMouseMove);
      document.addEventListener('mouseup', handleMouseUp);
      return () => {
        document.removeEventListener('mousemove', handleMouseMove);
        document.removeEventListener('mouseup', handleMouseUp);
      };
    }
  }, [isDragging]);

  // Memoized preview panel data
  const previewPanel = useMemo((): Panel => ({
    id: "preview",
    name: name || "Preview Panel",
    data: (() => {
      switch (panelType) {
        case "python":
          return {
            type: 'python',
            config: { code: pythonCode },
          } as PythonPanel;
        case "chart":
          return {
            type: 'chart',
            config: { 
              chartType, 
              dataSource, 
              xAxis, 
              yAxis, 
              title: chartTitle 
            },
          } as ChartPanel;
        case "text":
          return {
            type: 'text',
            config: { content: textContent, format: textFormat },
          } as TextPanel;
        case "metric":
          return {
            type: 'metric',
            config: { 
              metricName, 
              aggregation, 
              timeRange, 
              displayFormat 
            },
          } as MetricPanel;
        case "html":
          return {
            type: 'html',
            config: { 
              htmlContent, 
              allowScripts, 
              height: htmlHeight,
              cssIncludes,
              jsIncludes
            },
          } as HtmlPanel;
        default:
          return {
            type: 'python',
            config: { code: pythonCode },
          } as PythonPanel;
      }
    })(),
  }), [
    name, 
    panelType, 
    pythonCode, 
    chartType, 
    dataSource, 
    xAxis, 
    yAxis, 
    chartTitle, 
    textContent, 
    textFormat, 
    metricName, 
    aggregation, 
    timeRange, 
    displayFormat, 
    htmlContent, 
    allowScripts, 
    htmlHeight, 
    cssIncludes, 
    jsIncludes
  ]);

  // Memoized render functions
  const renderPreview = useCallback(() => {
    return (
      <div className="h-full bg-gray-50 rounded-lg p-4">
        <div className="mb-2">
          <h5 className="text-sm font-medium text-gray-700">Preview</h5>
          <p className="text-xs text-gray-500">Live preview of your panel</p>
        </div>
        <div className="bg-white rounded border h-full min-h-[300px] overflow-hidden">
          {panelType === "python" && (
            <PythonPanelComponent config={previewPanel.data.config as any} id="preview" />
          )}
          {panelType === "chart" && (
            <ChartPanelComponent config={previewPanel.data.config as any} id="preview" />
          )}
          {panelType === "text" && (
            <TextPanelComponent config={previewPanel.data.config as any} id="preview" />
          )}
          {panelType === "metric" && (
            <MetricPanelComponent config={previewPanel.data.config as any} id="preview" />
          )}
          {panelType === "html" && (
            <HtmlPanelComponent config={previewPanel.data.config as any} />
          )}
        </div>
      </div>
    );
  }, [panelType, previewPanel]);

  const renderPanelTypeConfig = useCallback(() => {
    switch (panelType) {
      case "python":
        return (
          <div className="mb-4 flex-1 min-h-0">
            <label className="block mb-1 font-medium">Python Code</label>
            <div className="border rounded overflow-hidden" style={{ height: '300px' }}>
              <Editor
                height="300px"
                defaultLanguage="python"
                value={pythonCode}
                onChange={(value) => setPythonCode(value || "")}
                theme="vs-light"
                options={{
                  minimap: { enabled: false },
                  scrollBeyondLastLine: false,
                  fontSize: 14,
                  lineNumbers: 'on',
                  roundedSelection: false,
                  scrollbar: {
                    vertical: 'visible',
                    horizontal: 'visible',
                  },
                  automaticLayout: true,
                  tabSize: 4,
                  insertSpaces: true,
                  wordWrap: 'on',
                }}
              />
            </div>
          </div>
        );
      
      case "chart":
        return (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block mb-1 font-medium">Chart Type</label>
                <select
                  className="w-full border rounded px-3 py-2"
                  value={chartType}
                  onChange={(e) => setChartType(e.target.value as any)}
                >
                  <option value="line">Line Chart</option>
                  <option value="bar">Bar Chart</option>
                  <option value="scatter">Scatter Plot</option>
                  <option value="histogram">Histogram</option>
                </select>
              </div>
              <div>
                <label className="block mb-1 font-medium">Data Source</label>
                <input
                  className="w-full border rounded px-3 py-2"
                  value={dataSource}
                  onChange={(e) => setDataSource(e.target.value)}
                  placeholder="experiment.metrics"
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block mb-1 font-medium">X Axis</label>
                <input
                  className="w-full border rounded px-3 py-2"
                  value={xAxis}
                  onChange={(e) => setXAxis(e.target.value)}
                  placeholder="timestamp"
                />
              </div>
              <div>
                <label className="block mb-1 font-medium">Y Axis</label>
                <input
                  className="w-full border rounded px-3 py-2"
                  value={yAxis}
                  onChange={(e) => setYAxis(e.target.value)}
                  placeholder="accuracy"
                />
              </div>
            </div>
            <div>
              <label className="block mb-1 font-medium">Chart Title (optional)</label>
              <input
                className="w-full border rounded px-3 py-2"
                value={chartTitle}
                onChange={(e) => setChartTitle(e.target.value)}
                placeholder="Chart title"
              />
            </div>
          </div>
        );
      
      case "text":
        return (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block mb-1 font-medium">Format</label>
                <select
                  className="w-full border rounded px-3 py-2"
                  value={textFormat}
                  onChange={(e) => setTextFormat(e.target.value as any)}
                >
                  <option value="markdown">Markdown</option>
                  <option value="html">HTML</option>
                  <option value="plain">Plain Text</option>
                </select>
              </div>
            </div>
            <div className="mb-4 flex-1 min-h-0">
              <label className="block mb-1 font-medium">Content</label>
              <div className="border rounded overflow-hidden" style={{ height: '250px' }}>
                <Editor
                  height="250px"
                  defaultLanguage={textFormat === "html" ? "html" : textFormat === "markdown" ? "markdown" : "plaintext"}
                  value={textContent}
                  onChange={(value) => setTextContent(value || "")}
                  theme="vs-light"
                  options={{
                    minimap: { enabled: false },
                    scrollBeyondLastLine: false,
                    fontSize: 14,
                    lineNumbers: 'on',
                    wordWrap: 'on',
                    automaticLayout: true,
                  }}
                />
              </div>
            </div>
          </div>
        );

      case "metric":
        return (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block mb-1 font-medium">Metric Name</label>
                <input
                  className="w-full border rounded px-3 py-2"
                  value={metricName}
                  onChange={(e) => setMetricName(e.target.value)}
                  placeholder="accuracy"
                />
              </div>
              <div>
                <label className="block mb-1 font-medium">Aggregation</label>
                <select
                  className="w-full border rounded px-3 py-2"
                  value={aggregation}
                  onChange={(e) => setAggregation(e.target.value as any)}
                >
                  <option value="avg">Average</option>
                  <option value="sum">Sum</option>
                  <option value="count">Count</option>
                  <option value="min">Minimum</option>
                  <option value="max">Maximum</option>
                </select>
              </div>
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block mb-1 font-medium">Time Range</label>
                <select
                  className="w-full border rounded px-3 py-2"
                  value={timeRange}
                  onChange={(e) => setTimeRange(e.target.value)}
                >
                  <option value="1h">Last Hour</option>
                  <option value="24h">Last 24 Hours</option>
                  <option value="7d">Last 7 Days</option>
                  <option value="30d">Last 30 Days</option>
                </select>
              </div>
              <div>
                <label className="block mb-1 font-medium">Display Format</label>
                <select
                  className="w-full border rounded px-3 py-2"
                  value={displayFormat}
                  onChange={(e) => setDisplayFormat(e.target.value as any)}
                >
                  <option value="number">Number</option>
                  <option value="percentage">Percentage</option>
                  <option value="currency">Currency</option>
                </select>
              </div>
            </div>
          </div>
        );

      case "html":
        return (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="flex items-center mb-1">
                  <input
                    type="checkbox"
                    checked={allowScripts}
                    onChange={(e) => setAllowScripts(e.target.checked)}
                    className="mr-2"
                  />
                  <span className="font-medium">Allow Scripts</span>
                </label>
              </div>
              <div>
                <label className="block mb-1 font-medium">Height (px)</label>
                <input
                  type="number"
                  className="w-full border rounded px-3 py-2"
                  value={htmlHeight}
                  onChange={(e) => setHtmlHeight(parseInt(e.target.value) || 400)}
                  min="200"
                  max="1000"
                />
              </div>
            </div>

            <div className="mb-4 flex-1 min-h-0">
              <label className="block mb-1 font-medium">HTML Content</label>
              <div className="border rounded overflow-hidden" style={{ height: '200px' }}>
                <Editor
                  height="200px"
                  defaultLanguage="html"
                  value={htmlContent}
                  onChange={(value) => setHtmlContent(value || "")}
                  theme="vs-light"
                  options={{
                    minimap: { enabled: false },
                    scrollBeyondLastLine: false,
                    fontSize: 14,
                    lineNumbers: 'on',
                    wordWrap: 'on',
                    automaticLayout: true,
                  }}
                />
              </div>
            </div>

            <div className="space-y-3">
              <div>
                <label className="block mb-1 font-medium">External CSS Libraries</label>
                <div className="flex gap-2 mb-2">
                  <input
                    className="flex-1 border rounded px-3 py-2"
                    value={newCssUrl}
                    onChange={(e) => setNewCssUrl(e.target.value)}
                    placeholder="https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css"
                  />
                  <Button onClick={addCssLibrary} type="button" size="sm">Add</Button>
                </div>
                {cssIncludes.length > 0 && (
                  <div className="space-y-1">
                    {cssIncludes.map((url, index) => (
                      <div key={index} className="flex items-center justify-between bg-gray-50 p-2 rounded text-sm">
                        <span className="truncate">{url}</span>
                        <button
                          onClick={() => removeCssLibrary(url)}
                          className="text-red-500 hover:text-red-700 ml-2"
                        >
                          Remove
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              <div>
                <label className="block mb-1 font-medium">External JavaScript Libraries</label>
                <div className="flex gap-2 mb-2">
                  <input
                    className="flex-1 border rounded px-3 py-2"
                    value={newJsUrl}
                    onChange={(e) => setNewJsUrl(e.target.value)}
                    placeholder="https://cdn.jsdelivr.net/npm/chart.js"
                  />
                  <Button onClick={addJsLibrary} type="button" size="sm">Add</Button>
                </div>
                {jsIncludes.length > 0 && (
                  <div className="space-y-1">
                    {jsIncludes.map((url, index) => (
                      <div key={index} className="flex items-center justify-between bg-gray-50 p-2 rounded text-sm">
                        <span className="truncate">{url}</span>
                        <button
                          onClick={() => removeJsLibrary(url)}
                          className="text-red-500 hover:text-red-700 ml-2"
                        >
                          Remove
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        );

      default:
        return null;
    }
  }, [
    panelType,
    pythonCode,
    chartType,
    dataSource,
    xAxis,
    yAxis,
    chartTitle,
    textFormat,
    textContent,
    metricName,
    aggregation,
    timeRange,
    displayFormat,
    allowScripts,
    htmlHeight,
    htmlContent,
    cssIncludes,
    jsIncludes,
    newCssUrl,
    newJsUrl,
    addCssLibrary,
    addJsLibrary,
    removeCssLibrary,
    removeJsLibrary
  ]);

  return (
    <div className={`fixed inset-0 z-50 flex items-center justify-center bg-black bg-opacity-40 ${open ? '' : 'hidden'}`}>
      <div className="bg-white rounded-lg shadow-xl w-full max-w-[95vw] max-h-[95vh] flex flex-col">
        {/* Header */}
        <div className="flex items-center justify-between p-6 border-b">
          <h3 className="comet-title-m">
            {isEditing ? "Edit Panel" : "Create new panel"}
          </h3>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-xl font-bold w-8 h-8 flex items-center justify-center"
          >
            ×
          </button>
        </div>

        {/* Content */}
        <div ref={containerRef} className="flex flex-1 min-h-0">
          {/* Left Sidebar - Panel Types */}
          <div className="w-64 border-r bg-gray-50 p-4">
            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-2">Panel Type</label>
              <div className="space-y-2">
                {panelTypes.map((type) => (
                  <button
                    key={type.value}
                    onClick={() => setPanelType(type.value as any)}
                    disabled={isEditing}
                    className={`w-full text-left p-3 rounded-lg border transition-all ${
                      panelType === type.value
                        ? 'bg-primary-100 border-primary text-primary'
                        : 'bg-white border-gray-200 text-gray-700 hover:bg-gray-50'
                    } ${isEditing ? 'opacity-50 cursor-not-allowed' : 'cursor-pointer'}`}
                  >
                    <div className="flex items-center gap-3">
                      <span className="text-lg">{type.icon}</span>
                      <span className="font-medium">{type.label}</span>
                    </div>
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Configuration Panel */}
          <div
            className="flex flex-col border-r bg-white"
            style={{ width: `${configWidth}%` }}
          >
            <div className="p-4 border-b">
              <h4 className="font-medium text-gray-900 mb-4">Panel Configuration</h4>
              
              <div className="mb-4">
                <label className="block mb-1 font-medium">Panel Name</label>
                <input
                  className="w-full border rounded px-3 py-2"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="Enter panel name"
                />
              </div>
            </div>

            <div className="flex-1 p-4 overflow-auto">
              {renderPanelTypeConfig()}
            </div>
          </div>

          {/* Resizable handle */}
          <div
            className="w-1 bg-gray-200 hover:bg-gray-300 cursor-col-resize relative"
            onMouseDown={handleMouseDown}
          >
            <div className="absolute inset-y-0 -left-1 -right-1" />
          </div>

          {/* Preview Panel */}
          <div
            className="flex flex-col bg-gray-50"
            style={{ width: `${100 - configWidth}%` }}
          >
            <div className="flex-1 p-4">
              {renderPreview()}
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center justify-between p-6 border-t bg-gray-50">
          <div className="text-sm text-gray-500">
            Configure your panel settings on the left and see a live preview on the right
          </div>
          <div className="flex gap-3">
            <Button
              onClick={onClose}
              variant="outline"
            >
              Cancel
            </Button>
            <Button
              onClick={handleSave}
              variant="default"
            >
              {isEditing ? "Update Panel" : "Create Panel"}
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
};

export default React.memo(PanelModal); 
