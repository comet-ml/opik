import React, { useState, useMemo } from "react";
import { Copy, Check, AlertCircle } from "lucide-react";
import { Textarea } from "@/components/ui/textarea";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  JsonDynamicBuilder,
  useJmesPathResult,
} from "@/components/shared/JsonDynamicBuilder";

const SAMPLE_JSON = {
  user: {
    name: "John Doe",
    email: "john@example.com",
    roles: ["admin", "user", "editor", "viewer"],
    profile: {
      age: 30,
      location: "New York",
    },
  },
  scores: [85, 92, 78, 95, 88, 91, 76, 89],
  metadata: {
    created_at: "2024-01-15",
    tags: ["important", "reviewed", "archived", "featured"],
  },
};

// JMESPath examples - cross-platform compatible syntax!
const JMESPATH_EXAMPLES = [
  { query: "user.name", description: "Get user name" },
  { query: "user.roles[0]", description: "Get first role" },
  { query: "user.roles[-1]", description: "Get last role" },
  { query: "scores[:3]", description: "First 3 scores" },
  { query: "scores[2:]", description: "Skip first 2 scores" },
  { query: "scores | sum(@)", description: "Sum of all scores" },
  { query: "scores | avg(@)", description: "Average score" },
  { query: "scores | min(@)", description: "Minimum score" },
  { query: "scores | max(@)", description: "Maximum score" },
  { query: "metadata.tags | reverse(@)", description: "Reverse tags" },
  { query: "user | keys(@)", description: "Get user object keys" },
  { query: "scores[:3] | sum(@)", description: "Sum of first 3 scores" },
  { query: "scores | length(@)", description: "Count of scores" },
  { query: "scores | sort(@)", description: "Sort scores" },
];

const JsonPathExplorerPage: React.FC = () => {
  const [jsonInput, setJsonInput] = useState<string>(
    JSON.stringify(SAMPLE_JSON, null, 2),
  );
  const [jmesPathQuery, setJmesPathQuery] = useState<string>("user.name");
  const [copiedPath, setCopiedPath] = useState<string | null>(null);

  const parsedJson = useMemo(() => {
    try {
      return { data: JSON.parse(jsonInput), error: null };
    } catch (e) {
      return { data: null, error: (e as Error).message };
    }
  }, [jsonInput]);

  // Use JMESPath for query results
  const queryResult = useJmesPathResult(parsedJson.data, jmesPathQuery);

  const handleCopyPath = (path: string) => {
    navigator.clipboard.writeText(path);
    setCopiedPath(path);
    setTimeout(() => setCopiedPath(null), 2000);
  };

  const handlePathClick = (path: string) => {
    setJmesPathQuery(path);
  };

  return (
    <div className="pt-6">
      <div className="mb-6">
        <h1 className="comet-title-l">JMESPath Explorer</h1>
        <p className="comet-body-s mt-2 text-muted-slate">
          Explore JSON structures using JMESPath - a cross-platform query language.
          Works with Java, Python, and JavaScript.{" "}
          <a
            href="https://jmespath.org/"
            target="_blank"
            rel="noopener noreferrer"
            className="text-primary hover:underline"
          >
            Learn more
          </a>
        </p>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        {/* Left Column - JSON Input */}
        <div className="flex flex-col gap-4">
          <div>
            <label className="comet-body-s-accented mb-2 block">
              JSON Input
            </label>
            <Textarea
              value={jsonInput}
              onChange={(e) => setJsonInput(e.target.value)}
              placeholder="Paste your JSON here..."
              className={cn(
                "min-h-[300px] font-mono text-xs",
                parsedJson.error && "border-destructive",
              )}
            />
            {parsedJson.error && (
              <div className="mt-2 flex items-center gap-2 text-sm text-destructive">
                <AlertCircle className="size-4" />
                <span>Invalid JSON: {parsedJson.error}</span>
              </div>
            )}
          </div>

          <div>
            <label className="comet-body-s-accented mb-2 block">
              JMESPath Query (click to open tree view)
            </label>
            <JsonDynamicBuilder
              data={parsedJson.data}
              value={jmesPathQuery}
              onValueChange={setJmesPathQuery}
              placeholder="Click to build JMESPath query..."
              showPreview={false}
            />
          </div>

          {jmesPathQuery && (
            <div>
              <label className="comet-body-s-accented mb-2 block">
                Query Result
              </label>
              <div
                className={cn(
                  "min-h-[100px] rounded-md border bg-muted p-3 font-mono text-xs",
                  queryResult.error && "border-destructive",
                )}
              >
                {queryResult.error ? (
                  <span className="text-destructive">{queryResult.error}</span>
                ) : queryResult.result !== undefined ? (
                  <pre className="whitespace-pre-wrap">
                    {JSON.stringify(queryResult.result, null, 2)}
                  </pre>
                ) : (
                  <span className="text-muted-slate">No result</span>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Right Column - JMESPath Examples */}
        <div className="flex flex-col gap-4">
          {/* JMESPath Examples */}
          <div>
            <label className="comet-body-s-accented mb-2 block">
              JMESPath Examples
            </label>
            <div className="max-h-[480px] overflow-y-auto rounded-md border bg-background">
              <div className="divide-y">
                {JMESPATH_EXAMPLES.map((example) => (
                  <div
                    key={example.query}
                    className="group flex items-center justify-between gap-2 px-3 py-2 hover:bg-muted"
                  >
                    <button
                      onClick={() => handlePathClick(example.query)}
                      className="flex flex-1 items-center justify-between gap-2 text-left"
                      title={`Click to use: ${example.query}`}
                    >
                      <span className="truncate font-mono text-xs text-foreground hover:text-primary">
                        {example.query}
                      </span>
                      <span className="shrink-0 text-xs text-muted-slate">
                        {example.description}
                      </span>
                    </button>
                    <Button
                      variant="ghost"
                      size="icon-xs"
                      onClick={() => handleCopyPath(example.query)}
                      className="opacity-0 group-hover:opacity-100"
                    >
                      {copiedPath === example.query ? (
                        <Check className="size-3 text-success" />
                      ) : (
                        <Copy className="size-3" />
                      )}
                    </Button>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

export default JsonPathExplorerPage;
