import React from "react";
import { ChartPanelConfig } from "./dashboardTypes";

interface ChartPanelProps {
  config: ChartPanelConfig;
  id: string;
}

// Mock data generators
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

const BarChart: React.FC<{ data: number[], labels: string[], title?: string }> = ({ data, labels, title }) => {
  const maxValue = Math.max(...data);
  const chartHeight = 200;
  const chartWidth = 300;
  const barWidth = chartWidth / data.length - 10;
  
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
                fill="#3b82f6"
                className="hover:fill-blue-600 transition-colors"
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
};

const LineChart: React.FC<{ data: number[], labels: string[], title?: string }> = ({ data, labels, title }) => {
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
  
  return (
    <div className="text-center">
      <h4 className="font-medium mb-4">{title || 'Line Chart'}</h4>
      <svg width={chartWidth + 40} height={chartHeight + 60} className="mx-auto">
        {/* Chart line */}
        <path
          d={pathData}
          stroke="#3b82f6"
          strokeWidth="2"
          fill="none"
          className="hover:stroke-blue-600 transition-colors"
        />
        
        {/* Data points */}
        {points.map((point, index) => (
          <g key={index}>
            <circle
              cx={point.x}
              cy={point.y}
              r="4"
              fill="#3b82f6"
              className="hover:fill-blue-600 transition-colors"
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
};

const ScatterPlot: React.FC<{ data: {x: number, y: number}[], title?: string }> = ({ data, title }) => {
  const maxX = Math.max(...data.map(d => d.x));
  const maxY = Math.max(...data.map(d => d.y));
  const chartHeight = 200;
  const chartWidth = 300;
  
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
            fill="#3b82f6"
            className="hover:fill-blue-600 transition-colors"
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
};

const Histogram: React.FC<{ data: number[], labels: string[], title?: string }> = ({ data, labels, title }) => {
  const maxValue = Math.max(...data);
  const chartHeight = 200;
  const chartWidth = 300;
  const barWidth = chartWidth / data.length - 2;
  
  return (
    <div className="text-center">
      <h4 className="font-medium mb-4">{title || 'Histogram'}</h4>
      <svg width={chartWidth + 40} height={chartHeight + 60} className="mx-auto">
        {/* Histogram bars */}
        {data.map((value, index) => {
          const barHeight = (value / maxValue) * chartHeight;
          const x = index * (barWidth + 2) + 20;
          const y = chartHeight - barHeight + 20;
          
          return (
            <g key={index}>
              <rect
                x={x}
                y={y}
                width={barWidth}
                height={barHeight}
                fill="#10b981"
                className="hover:fill-green-600 transition-colors"
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
                transform={`rotate(-45, ${x + barWidth / 2}, ${chartHeight + 35})`}
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
};

const ChartPanel: React.FC<ChartPanelProps> = ({ config, id }) => {
  const { chartType, dataSource, xAxis, yAxis, title } = config;
  const mockData = generateMockData(chartType);

  const renderChart = () => {
    switch (chartType) {
      case 'bar':
        return (
          <BarChart 
            data={mockData.data as number[]} 
            labels={mockData.labels} 
            title={title}
          />
        );
      case 'line':
        return (
          <LineChart 
            data={mockData.data as number[]} 
            labels={mockData.labels} 
            title={title}
          />
        );
      case 'scatter':
        return (
          <ScatterPlot 
            data={mockData.data as {x: number, y: number}[]} 
            title={title}
          />
        );
      case 'histogram':
        return (
          <Histogram 
            data={mockData.data as number[]} 
            labels={mockData.labels} 
            title={title}
          />
        );
      default:
        return (
          <div className="text-center p-8">
            <div className="text-4xl mb-2">📊</div>
            <p className="text-gray-600">Unknown chart type: {chartType}</p>
          </div>
        );
    }
  };

  return (
    <div className="h-full flex flex-col bg-background p-4">
      <div className="flex-1 flex items-center justify-center">
        {renderChart()}
      </div>
      
      {/* Chart configuration info */}
      <div className="mt-4 p-3 bg-accent/50 rounded-md border">
        <div className="grid grid-cols-2 gap-2 comet-body-xs text-muted-foreground">
          <div><strong>Data Source:</strong> {dataSource || "Mock data"}</div>
          <div><strong>Type:</strong> {chartType}</div>
          <div><strong>X Axis:</strong> {xAxis || "Categories"}</div>
          <div><strong>Y Axis:</strong> {yAxis || "Values"}</div>
        </div>
      </div>
    </div>
  );
};

export default ChartPanel; 