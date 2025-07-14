import React, { useCallback, useMemo, useState } from "react";
import { Layers3, ChevronDown } from "lucide-react";

import ObservabilitySection from "@/components/pages/HomePage/ObservabilitySection";
import EvaluationSection from "@/components/pages/HomePage/EvaluationSection";
import GetStartedSection from "@/components/pages/HomePage/GetStartedSection";
import LoadableSelectBox from "@/components/shared/LoadableSelectBox/LoadableSelectBox";
import NoData from "@/components/shared/NoData/NoData";
import { Button } from "@/components/ui/button";
import Loader from "@/components/shared/Loader/Loader";
import useDashboardTemplates from "@/api/dashboardTemplates/useDashboardTemplates";
import useDashboardTemplatesById from "@/api/dashboardTemplates/useDashboardTemplatesById";
import { DropdownOption } from "@/types/shared";
import { calculateWorkspaceName } from "@/lib/utils";
import useAppStore from "@/store/AppStore";
import PythonPanel from "../CompareExperimentsPage/PythonPanel";
import ChartPanel from "../CompareExperimentsPage/ChartPanel";
import TextPanel from "../CompareExperimentsPage/TextPanel";
import MetricPanel from "../CompareExperimentsPage/MetricPanel";
import HtmlPanel from "../CompareExperimentsPage/HtmlPanel";

const OldHomePage = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Dashboard template state
  const [selectedDashboardTemplateId, setSelectedDashboardTemplateId] = useState<string>("");
  const [viewMode, setViewMode] = useState<"home" | "dashboard">("home");

  const { data: dashboardTemplates, isLoading: templatesLoading } = useDashboardTemplates(
    {},
    {
      retry: 1,
      staleTime: 5 * 60 * 1000,
    }
  );

  const { data: selectedTemplate, isLoading: selectedTemplateLoading } = useDashboardTemplatesById(
    { dashboardTemplateId: selectedDashboardTemplateId },
    { 
      enabled: !!selectedDashboardTemplateId,
      retry: 1,
      staleTime: 5 * 60 * 1000,
    }
  );

  // Dashboard template dropdown options
  const dashboardTemplateOptions: DropdownOption<string>[] = useMemo(() => {
    if (!dashboardTemplates) return [];
    return [
      { value: "", label: "None (Show home view)" },
      ...dashboardTemplates.map((template) => ({
        value: template.id,
        label: template.name,
      })),
    ];
  }, [dashboardTemplates]);

  const handleDashboardTemplateChange = useCallback((templateId: string) => {
    setSelectedDashboardTemplateId(templateId);
    setViewMode(templateId ? "dashboard" : "home");
  }, []);

  const renderDashboardView = useCallback(() => {
    if (!selectedTemplate) return null;

    // Convert template sections to display format
    const displaySections = selectedTemplate.configuration.sections.map(section => ({
      ...section,
      isExpanded: true, // Show all sections expanded by default
    }));

    const renderPanelContent = (panel: any) => {
      switch (panel.type.toLowerCase()) {
        case "python":
          return (
            <PythonPanel 
              config={{ code: panel.configuration?.code || "# Python code will be executed here" }} 
              id={panel.id} 
              isTemplate={true}
            />
          );
        case "metric":
          return (
            <MetricPanel 
              config={{
                metricName: panel.configuration?.metricName || "Metric",
                aggregation: panel.configuration?.aggregation || "avg",
                timeRange: panel.configuration?.timeRange || "24h",
                displayFormat: panel.configuration?.displayFormat || "number"
              }} 
              id={panel.id} 
            />
          );
        case "chart":
          return (
            <ChartPanel 
              config={{
                chartType: panel.configuration?.chartType || "line",
                dataSource: panel.configuration?.dataSource || "",
                xAxis: panel.configuration?.xAxis || "",
                yAxis: panel.configuration?.yAxis || "",
                title: panel.configuration?.title || "Chart"
              }} 
              id={panel.id} 
            />
          );
        case "text":
          return (
            <TextPanel 
              config={{
                content: panel.configuration?.content || "Text content will be displayed here",
                format: panel.configuration?.format || "markdown"
              }} 
              id={panel.id} 
            />
          );
        case "html":
          return (
            <HtmlPanel 
              config={{
                htmlContent: panel.configuration?.htmlContent || "<div>HTML content</div>",
                allowScripts: panel.configuration?.allowScripts || true,
                height: panel.configuration?.height || 400,
                cssIncludes: panel.configuration?.cssIncludes || [],
                jsIncludes: panel.configuration?.jsIncludes || []
              }} 
            />
          );
        default:
          return (
            <div className="h-full flex items-center justify-center text-red-500">
              <div className="text-center">
                <div className="text-4xl mb-2">⚠️</div>
                <p>Unsupported panel type: {panel.type}</p>
              </div>
            </div>
          );
      }
    };

    const renderPanelHeader = (panel: any) => (
      <div className="flex items-center justify-between px-4 py-3 border-b bg-background">
        <div className="flex items-center gap-3">
          <span className="comet-body-s font-medium text-foreground">{panel.name}</span>
          <span className="comet-body-xs bg-muted text-muted-foreground px-2 py-1 rounded-sm">
            {panel.type}
          </span>
        </div>
        <div className="flex items-center gap-2">
          <span className="comet-body-xs text-muted-foreground">
            Preview
          </span>
        </div>
      </div>
    );

    const renderSectionHeader = (section: any) => (
      <div className="flex items-center justify-between p-4 border-b bg-white rounded-t-lg">
        <div className="flex items-center gap-2">
          <button
            className="p-1 hover:bg-muted/50 rounded transition-colors"
            disabled
          >
            <ChevronDown size={16} />
          </button>
          
          <div className="flex items-center gap-2">
            <h3 className="comet-title-m">{section.title}</h3>
            <span className="text-xs text-gray-500 bg-gray-200 px-2 py-1 rounded">
              {section.panels.length} panel{section.panels.length !== 1 ? 's' : ''}
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-gray-500">Template Preview</span>
        </div>
      </div>
    );

    const renderSectionContent = (section: any) => {
      if (section.panels.length === 0) {
        return (
          <div className="text-center py-8 text-gray-500">
            <div className="text-4xl mb-2">📊</div>
            <p>No panels in this section.</p>
          </div>
        );
      }

      // Create a simple grid layout for panels
      const gridCols = section.panels.length === 1 ? 1 : Math.min(2, section.panels.length);
      const gridClass = gridCols === 1 ? "grid-cols-1" : "grid-cols-2";

      return (
        <div className={`grid ${gridClass} gap-4`}>
          {section.panels.map((panel: any) => (
            <div 
              key={panel.id} 
              className="bg-card rounded-md border shadow-sm overflow-hidden"
              style={{ minHeight: "300px" }}
            >
              {renderPanelHeader(panel)}
              
              {/* Panel Content */}
              <div className="h-[calc(300px-65px)]">
                {renderPanelContent(panel)}
              </div>
            </div>
          ))}
        </div>
      );
    };

    return (
      <div className="space-y-6">
        {/* Template Header */}
        <div className="flex items-center justify-between px-6 py-6 border-b bg-background">
          <div>
            <h2 className="comet-title-l">{selectedTemplate.name}</h2>
            <p className="text-sm text-gray-600 mt-1">
              {selectedTemplate.description || "Template Preview"}
            </p>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setViewMode("home")}
          >
            Back to Home
          </Button>
        </div>

        {/* Dashboard Sections */}
        <div>
          {displaySections.length === 0 ? (
            <div className="mx-6">
              <NoData
                icon={<Layers3 className="size-16 text-muted-slate" />}
                title="Empty Template"
                message="This dashboard template doesn't have any sections yet."
                className="rounded-lg border bg-card"
              />
            </div>
          ) : (
            displaySections.map((section) => (
              <div key={section.id} className="border rounded-lg bg-white shadow-sm hover:shadow-md transition-shadow mb-6 mx-6">
                {renderSectionHeader(section)}
                
                <div className="p-4">
                  {renderSectionContent(section)}
                </div>
              </div>
            ))
          )}
        </div>
      </div>
    );
  }, [selectedTemplate]);

  if (selectedTemplateLoading && selectedDashboardTemplateId) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      {/* Dashboard Template Selector */}
      <div className="mb-4">
        <div className="flex items-center gap-4">
          <div className="flex-1 max-w-sm">
            <LoadableSelectBox
              isLoading={templatesLoading}
              options={dashboardTemplateOptions}
              placeholder="Select a dashboard template..."
              value={selectedDashboardTemplateId}
              onChange={handleDashboardTemplateChange}
              disabled={templatesLoading}
            />
          </div>
          {selectedDashboardTemplateId && (
            <Button
              variant={viewMode === "dashboard" ? "default" : "outline"}
              size="sm"
              onClick={() => setViewMode("dashboard")}
            >
              <Layers3 className="mr-2 size-4" />
              Dashboard View
            </Button>
          )}
          {selectedDashboardTemplateId && (
            <Button
              variant={viewMode === "home" ? "default" : "outline"}
              size="sm"
              onClick={() => setViewMode("home")}
            >
              Home View
            </Button>
          )}
        </div>
      </div>

      {/* Render Dashboard View or Home View */}
      {viewMode === "dashboard" && selectedTemplate ? (
        renderDashboardView()
      ) : (
        <>
          <div className="mb-4 flex items-center justify-between">
            <h1 className="comet-title-l truncate break-words">
              Welcome to {calculateWorkspaceName(workspaceName, "Opik")}
            </h1>
          </div>
          <GetStartedSection />
          <ObservabilitySection />
          <EvaluationSection />
        </>
      )}
    </div>
  );
};

export default OldHomePage;
