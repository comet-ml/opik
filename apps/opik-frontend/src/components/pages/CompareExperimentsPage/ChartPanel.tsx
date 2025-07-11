import React, { useMemo } from "react";
import { BarChart3 } from "lucide-react";
import { ChartPanelConfig } from "./dashboardTypes";

interface ChartPanelProps {
  config: ChartPanelConfig;
  id: string;
}

// Mock data generators - memoized outside component to avoid recreation
const generateMockData = (chartType: string, count: number = 6) => {
  const labels = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun'];
  const data = Array.from({ length: count }, () => Math.floor(Math.random() * 100) + 10);
  
  switch (chartType) {
    case 'scatter':
      return {
        labels,
        data: Array.from({ length: count }, () => ({
          x: Math.floor(Math.random() * 100),
          y: Math.floor(Math.random() * 100)
        }))
      };
    case 'histogram':
      return {
        labels: ['0-10', '10-20', '20-30', '30-40', '40-50', '50-60'],
        data: [12, 19, 23, 17, 14, 8]
      };
    default:
      return { labels, data };
  }
};

const BarChart = React.memo<{ data: number[], labels: string[], title?: string }>(({ data, labels, title }) => {
  const chartConfig = useMemo(() => {
    const maxValue = Math.max(...data);
    const chartHeight = 200;
    const chartWidth = 300;
    const barWidth = chartWidth / data.length - 10;
    
    return { maxValue, chartHeight, chartWidth, barWidth };
  }, [data]);

  const { maxValue, chartHeight, chartWidth, barWidth } = chartConfig;
  
  return (
    <div className="text-center">
      <h4 className="font-medium mb-4">{title || 'Bar Chart'}</h4>
      <svg width={chartWidth + 40} height={chartHeight + 60} className="mx-auto">
        {/* Chart bars */}
        {data.map((value, index) => {
          const barHeight = (value / maxValue) * chartHeight;
          const x = index * (barWidth + 10) + 20;
          const y = chartHeight - barHeight + 20;
          
          return (
            <g key={index}>
              <rect
                x={x}
                y={y}
                width={barWidth}
                height={barHeight}
                fill="#5155F5"
                className="hover:fill-primary transition-colors"
              />
              <text
                x={x + barWidth / 2}
                y={y - 5}
                textAnchor="middle"
                className="text-xs fill-gray-600"
              >
                {value}
              </text>
              <text
                x={x + barWidth / 2}
                y={chartHeight + 35}
                textAnchor="middle"
                className="text-xs fill-gray-600"
              >
                {labels[index]}
              </text>
            </g>
          );
        })}
        
        {/* Y-axis */}
        <line x1="20" y1="20" x2="20" y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
        {/* X-axis */}
        <line x1="20" y1={chartHeight + 20} x2={chartWidth + 20} y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
      </svg>
    </div>
  );
});

const LineChart = React.memo<{ data: number[], labels: string[], title?: string }>(({ data, labels, title }) => {
  const chartConfig = useMemo(() => {
    const maxValue = Math.max(...data);
    const chartHeight = 200;
    const chartWidth = 300;
    const stepX = chartWidth / (data.length - 1);
    
    const points = data.map((value, index) => ({
      x: index * stepX + 20,
      y: chartHeight - (value / maxValue) * chartHeight + 20
    }));
    
    const pathData = points.map((point, index) => 
      `${index === 0 ? 'M' : 'L'} ${point.x} ${point.y}`
    ).join(' ');
    
    return { maxValue, chartHeight, chartWidth, points, pathData };
  }, [data]);

  const { chartHeight, chartWidth, points, pathData } = chartConfig;
  
  return (
    <div className="text-center">
      <h4 className="font-medium mb-4">{title || 'Line Chart'}</h4>
      <svg width={chartWidth + 40} height={chartHeight + 60} className="mx-auto">
        {/* Chart line */}
        <path
          d={pathData}
          stroke="#5155F5"
          strokeWidth="2"
          fill="none"
          className="hover:stroke-primary transition-colors"
        />
        
        {/* Data points */}
        {points.map((point, index) => (
          <g key={index}>
            <circle
              cx={point.x}
              cy={point.y}
              r="4"
              fill="#5155F5"
              className="hover:fill-primary transition-colors"
            />
            <text
              x={point.x}
              y={point.y - 10}
              textAnchor="middle"
              className="text-xs fill-gray-600"
            >
              {data[index]}
            </text>
            <text
              x={point.x}
              y={chartHeight + 35}
              textAnchor="middle"
              className="text-xs fill-gray-600"
            >
              {labels[index]}
            </text>
          </g>
        ))}
        
        {/* Axes */}
        <line x1="20" y1="20" x2="20" y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
        <line x1="20" y1={chartHeight + 20} x2={chartWidth + 20} y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
      </svg>
    </div>
  );
});

const ScatterPlot = React.memo<{ data: {x: number, y: number}[], title?: string }>(({ data, title }) => {
  const chartConfig = useMemo(() => {
    const maxX = Math.max(...data.map(d => d.x));
    const maxY = Math.max(...data.map(d => d.y));
    const chartHeight = 200;
    const chartWidth = 300;
    
    return { maxX, maxY, chartHeight, chartWidth };
  }, [data]);

  const { maxX, maxY, chartHeight, chartWidth } = chartConfig;
  
  return (
    <div className="text-center">
      <h4 className="font-medium mb-4">{title || 'Scatter Plot'}</h4>
      <svg width={chartWidth + 40} height={chartHeight + 60} className="mx-auto">
        {/* Data points */}
        {data.map((point, index) => (
          <circle
            key={index}
            cx={(point.x / maxX) * chartWidth + 20}
            cy={chartHeight - (point.y / maxY) * chartHeight + 20}
            r="4"
            fill="#5155F5"
            className="hover:fill-primary transition-colors"
          />
        ))}
        
        {/* Axes */}
        <line x1="20" y1="20" x2="20" y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
        <line x1="20" y1={chartHeight + 20} x2={chartWidth + 20} y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
        
        {/* Axis labels */}
        <text x={chartWidth / 2 + 20} y={chartHeight + 50} textAnchor="middle" className="text-xs fill-gray-600">
          X Axis
        </text>
        <text x="10" y={chartHeight / 2 + 20} textAnchor="middle" className="text-xs fill-gray-600" transform={`rotate(-90, 10, ${chartHeight / 2 + 20})`}>
          Y Axis
        </text>
      </svg>
    </div>
  );
});

const Histogram = React.memo<{ data: number[], labels: string[], title?: string }>(({ data, labels, title }) => {
  const chartConfig = useMemo(() => {
    const maxValue = Math.max(...data);
    const chartHeight = 200;
    const chartWidth = 300;
    const barWidth = chartWidth / data.length - 2;
    
    return { maxValue, chartHeight, chartWidth, barWidth };
  }, [data]);

  const { maxValue, chartHeight, chartWidth, barWidth } = chartConfig;
  
  return (
    <div className="text-center">
      <h4 className="font-medium mb-4">{title || 'Histogram'}</h4>
      <svg width={chartWidth + 40} height={chartHeight + 60} className="mx-auto">
        {/* Histogram bars */}
        {data.map((value, index) => {
          const barHeight = (value / maxValue) * chartHeight;
          const x = index * barWidth + 20;
          const y = chartHeight - barHeight + 20;
          
          return (
            <g key={index}>
              <rect
                x={x}
                y={y}
                width={barWidth - 1}
                height={barHeight}
                fill="#5155F5"
                className="hover:fill-primary transition-colors"
              />
              <text
                x={x + barWidth / 2}
                y={y - 5}
                textAnchor="middle"
                className="text-xs fill-gray-600"
              >
                {value}
              </text>
              <text
                x={x + barWidth / 2}
                y={chartHeight + 35}
                textAnchor="middle"
                className="text-xs fill-gray-600"
              >
                {labels[index]}
              </text>
            </g>
          );
        })}
        
        {/* Axes */}
        <line x1="20" y1="20" x2="20" y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
        <line x1="20" y1={chartHeight + 20} x2={chartWidth + 20} y2={chartHeight + 20} stroke="#e5e7eb" strokeWidth="1" />
      </svg>
    </div>
  );
});

const ChartPanel: React.FC<ChartPanelProps> = ({ config, id }) => {
  // Memoize chart data generation
  const chartData = useMemo(() => {
    return generateMockData(config.chartType);
  }, [config.chartType]);

  // Memoize chart configuration
  const chartConfig = useMemo(() => ({
    title: config.title || `${config.chartType} Chart`,
    dataSource: config.dataSource || "sample data",
    xAxis: config.xAxis || "X Axis",
    yAxis: config.yAxis || "Y Axis",
  }), [config.title, config.chartType, config.dataSource, config.xAxis, config.yAxis]);

  // Memoize chart renderer
  const renderedChart = useMemo(() => {
    switch (config.chartType) {
      case "bar":
        return (
          <BarChart 
            data={chartData.data as number[]} 
            labels={chartData.labels} 
            title={chartConfig.title} 
          />
        );
      case "line":
        return (
          <LineChart 
            data={chartData.data as number[]} 
            labels={chartData.labels} 
            title={chartConfig.title} 
          />
        );
      case "scatter":
        return (
          <ScatterPlot 
            data={chartData.data as {x: number, y: number}[]} 
            title={chartConfig.title} 
          />
        );
      case "histogram":
        return (
          <Histogram 
            data={chartData.data as number[]} 
            labels={chartData.labels} 
            title={chartConfig.title} 
          />
        );
      default:
        return (
          <div className="flex items-center justify-center h-full text-red-500">
            <div className="text-center">
              <BarChart3 className="mx-auto mb-2" size={48} />
              <p>Unsupported chart type: {config.chartType}</p>
            </div>
          </div>
        );
    }
  }, [config.chartType, chartData, chartConfig]);

  return (
    <div className="h-full flex flex-col bg-background">
      {/* Status Header */}
      <div className="p-3 bg-blue-100/50 border-b">
        <div className="flex items-center gap-2">
          <BarChart3 className="text-blue-600" size={16} />
          <span className="comet-body-s text-blue-600">Chart Panel</span>
          <span className="comet-body-xs text-muted-foreground bg-muted px-2 py-1 rounded-sm">
            Mock Data
          </span>
        </div>
      </div>

      {/* Chart Content */}
      <div className="flex-1 p-4 flex items-center justify-center">
        {renderedChart}
      </div>

      {/* Configuration Info */}
      <div className="p-3 bg-accent/50 border-t">
        <div className="comet-body-xs text-muted-foreground space-y-1">
          <p><strong>Data Source:</strong> {chartConfig.dataSource}</p>
          <p><strong>X-Axis:</strong> {chartConfig.xAxis} | <strong>Y-Axis:</strong> {chartConfig.yAxis}</p>
          <p className="text-center mt-2">📊 Chart will connect to real data when backend is integrated</p>
        </div>
      </div>
    </div>
  );
};

export default React.memo(ChartPanel); 
