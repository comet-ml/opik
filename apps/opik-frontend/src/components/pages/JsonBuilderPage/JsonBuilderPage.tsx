import React, { useState, useCallback, useMemo, useRef } from "react";
import { Braces } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  JsonTreePopover,
  JsonObject,
  JsonValue,
} from "@/components/shared/JsonTreePopover";
import LLMPromptMessage, {
  LLMPromptMessageHandle,
} from "@/components/pages-shared/llm/LLMPromptMessages/LLMPromptMessage";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";

// LLM Messages Section with JsonTreePopover integration
interface LLMMessagesSectionProps {
  jsonData: JsonObject | null;
  onPathSelect?: (path: string, value: JsonValue) => void;
}

const LLMMessagesSection: React.FC<LLMMessagesSectionProps> = ({
  jsonData,
  onPathSelect,
}) => {
  const [messages, setMessages] = useState<LLMMessage[]>(() => [
    generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.system }),
    generateDefaultLLMPromptMessage({ role: LLM_MESSAGE_ROLE.user }),
  ]);
  const messageRefsMap = useRef<Map<string, LLMPromptMessageHandle>>(new Map());

  const handleChangeMessage = useCallback(
    (messageId: string, changes: Partial<LLMMessage>) => {
      setMessages((prev) =>
        prev.map((m) => (m.id !== messageId ? m : { ...m, ...changes })),
      );
    },
    [],
  );

  const handleRemoveMessage = useCallback((messageId: string) => {
    setMessages((prev) => prev.filter((m) => m.id !== messageId));
  }, []);

  const handleDuplicateMessage = useCallback(
    (message: LLMMessage, position: number) => {
      const newMessage = generateDefaultLLMPromptMessage({
        role: message.role,
        content: message.content,
      });
      setMessages((prev) => {
        const newMessages = [...prev];
        newMessages.splice(position, 0, newMessage);
        return newMessages;
      });
    },
    [],
  );

  const handleMessageFocus = useCallback((messageId: string) => {
    setFocusedMessageId(messageId);
  }, []);

  // Extract variable names from JSON for display
  const jsonVariables = useMemo(() => {
    if (!jsonData) return [];
    return Object.keys(jsonData);
  }, [jsonData]);

  return (
    <div className="flex flex-col gap-4">
      {/* Messages */}
      <div className="flex flex-col gap-2">
        {messages.map((message, idx) => (
          <LLMPromptMessage
            key={message.id}
            ref={(handle) =>
              handle
                ? messageRefsMap.current.set(message.id, handle)
                : messageRefsMap.current.delete(message.id)
            }
            message={message}
            hideRemoveButton={messages.length === 1}
            hideDragButton={true}
            hidePromptActions={true}
            onRemoveMessage={() => handleRemoveMessage(message.id)}
            onDuplicateMessage={() => handleDuplicateMessage(message, idx + 1)}
            onChangeMessage={(changes) =>
              handleChangeMessage(message.id, changes)
            }
            onFocus={() => handleMessageFocus(message.id)}
            promptVariables={jsonVariables}
            jsonTreeData={jsonData}
            onJsonPathSelect={onPathSelect}
          />
        ))}
      </div>

      {/* Help text */}
      <p className="text-xs text-muted-foreground">
        Type <code className="rounded bg-muted px-1">{"{{"}</code> to open the
        variable picker, or click &quot;Insert Variable&quot; to browse JSON
        paths.
      </p>
    </div>
  );
};

// Example JSON objects
const JSON_EXAMPLES = [
  {
    name: "User Profile",
    json: `{
  "user": {
    "id": "12345",
    "name": "John Doe",
    "email": "john@example.com",
    "profile": {
      "age": 30,
      "location": {
        "city": "San Francisco",
        "country": "USA",
        "coordinates": {
          "lat": 37.7749,
          "lng": -122.4194
        }
      },
      "preferences": {
        "theme": "dark",
        "notifications": true
      }
    }
  },
  "metadata": {
    "created_at": "2024-01-15T10:30:00Z",
    "updated_at": "2024-01-20T15:45:00Z",
    "version": 2
  },
  "tags": ["premium", "verified", "active"]
}`,
  },
  {
    name: "LLM Response",
    json: `{
  "input": {
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "What is the capital of France?"}
    ],
    "model": "gpt-4",
    "temperature": 0.7
  },
  "output": {
    "choices": [
      {
        "message": {
          "role": "assistant",
          "content": "The capital of France is Paris."
        },
        "finish_reason": "stop"
      }
    ],
    "usage": {
      "prompt_tokens": 25,
      "completion_tokens": 10,
      "total_tokens": 35
    }
  },
  "metadata": {
    "latency_ms": 450,
    "trace_id": "abc123"
  }
}`,
  },
  {
    name: "E-commerce Order",
    json: `{
  "order": {
    "id": "ORD-2024-001",
    "status": "shipped",
    "customer": {
      "name": "Alice Smith",
      "email": "alice@example.com",
      "address": {
        "street": "123 Main St",
        "city": "New York",
        "zip": "10001"
      }
    },
    "items": [
      {"sku": "PROD-A", "name": "Widget", "quantity": 2, "price": 29.99},
      {"sku": "PROD-B", "name": "Gadget", "quantity": 1, "price": 49.99}
    ],
    "totals": {
      "subtotal": 109.97,
      "tax": 9.90,
      "shipping": 5.99,
      "total": 125.86
    }
  },
  "tracking": {
    "carrier": "FedEx",
    "number": "1234567890",
    "estimated_delivery": "2024-01-25"
  }
}`,
  },
  {
    name: "API Error",
    json: `{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Invalid request parameters",
    "details": [
      {"field": "email", "issue": "Invalid email format"},
      {"field": "age", "issue": "Must be a positive number"}
    ]
  },
  "request_id": "req_abc123",
  "timestamp": "2024-01-20T10:30:00Z"
}`,
  },
  {
    name: "Deep Nested",
    json: `{
  "company": {
    "name": "TechCorp Inc.",
    "departments": {
      "engineering": {
        "teams": {
          "frontend": {
            "lead": {
              "name": "Sarah Chen",
              "contact": {
                "email": "sarah@techcorp.com",
                "phone": {
                  "office": "+1-555-0100",
                  "mobile": "+1-555-0101"
                }
              }
            },
            "projects": [
              {
                "name": "Dashboard Redesign",
                "status": {
                  "phase": "development",
                  "progress": {
                    "completed": 65,
                    "remaining": 35,
                    "milestones": {
                      "design": {"done": true, "date": "2024-01-10"},
                      "development": {"done": false, "eta": "2024-02-15"},
                      "testing": {"done": false, "eta": "2024-03-01"}
                    }
                  }
                },
                "resources": {
                  "budget": {
                    "allocated": 50000,
                    "spent": 32500,
                    "breakdown": {
                      "personnel": 25000,
                      "tools": 5000,
                      "infrastructure": 2500
                    }
                  }
                }
              }
            ]
          },
          "backend": {
            "lead": {
              "name": "Mike Johnson",
              "contact": {
                "email": "mike@techcorp.com"
              }
            }
          }
        },
        "metrics": {
          "performance": {
            "velocity": {
              "current": 42,
              "average": 38,
              "trend": {
                "direction": "up",
                "percentage": 10.5
              }
            }
          }
        }
      }
    }
  }
}`,
  },
  {
    name: "Long Keys & Values",
    json: `{
  "user_authentication_and_authorization_settings": {
    "multi_factor_authentication_configuration": {
      "primary_authentication_method_identifier": "time_based_one_time_password_totp",
      "backup_authentication_recovery_codes_enabled": true,
      "session_expiration_timeout_in_milliseconds": 3600000,
      "remember_device_for_trusted_authentication_days": 30
    },
    "role_based_access_control_permissions": {
      "administrator_full_system_access_permission": {
        "permission_identifier_string": "admin.full.access.all.resources",
        "permission_description_for_documentation": "Grants complete administrative access to all system resources, configurations, and user management capabilities",
        "is_permission_currently_active_and_enabled": true
      },
      "standard_user_limited_access_permission": {
        "permission_identifier_string": "user.standard.limited.read.write",
        "permission_description_for_documentation": "Provides standard user access with read and write capabilities for assigned resources only",
        "allowed_resource_types_for_this_permission": [
          "documents_and_files_storage",
          "user_profile_information",
          "team_collaboration_spaces"
        ]
      }
    }
  },
  "application_configuration_and_feature_flags": {
    "experimental_features_beta_testing_enabled": {
      "new_dashboard_redesign_with_analytics": true,
      "artificial_intelligence_powered_suggestions": false,
      "real_time_collaboration_editing_feature": true
    },
    "performance_optimization_settings_configuration": {
      "enable_lazy_loading_for_images_and_media": true,
      "cache_duration_for_static_assets_seconds": 86400,
      "maximum_concurrent_api_requests_allowed": 10,
      "request_timeout_before_automatic_retry_ms": 30000
    }
  },
  "detailed_error_message_with_stack_trace_information": "TypeError: Cannot read properties of undefined (reading 'user_authentication_and_authorization_settings') at AuthenticationService.validateUserCredentials (auth-service.js:142:23) at async LoginController.handleUserLogin (login-controller.js:89:15)"
}`,
  },
];

// Main JSON Builder component
const JsonBuilderPage: React.FC = () => {
  const [jsonInput, setJsonInput] = useState<string>(JSON_EXAMPLES[0].json);
  const [jsonError, setJsonError] = useState<string | null>(null);

  // Parse JSON input
  const parsedJson = useMemo<JsonObject | null>(() => {
    try {
      const parsed = JSON.parse(jsonInput);
      if (typeof parsed === "object" && parsed !== null) {
        setJsonError(null);
        return parsed as JsonObject;
      }
      setJsonError("Input must be a JSON object or array");
      return null;
    } catch (e) {
      setJsonError(e instanceof Error ? e.message : "Invalid JSON");
      return null;
    }
  }, [jsonInput]);

  const handlePathSelect = useCallback(() => {
    // Path selected - can be used for tracking or other purposes
  }, []);

  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-6 p-6">
      <div>
        <h1 className="comet-title-l mb-2">JsonTreePopover Demo</h1>
        <p className="text-muted-foreground">
          Enter a JSON object below and use the button to explore its structure
          in a popup.
        </p>
      </div>

      {/* Example Buttons */}
      <Card>
        <CardHeader>
          <CardTitle>Example JSON Objects</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex flex-wrap gap-2">
            {JSON_EXAMPLES.map((example) => (
              <Button
                key={example.name}
                variant="outline"
                size="sm"
                onClick={() => {
                  setJsonInput(example.json);
                }}
              >
                {example.name}
              </Button>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* JSON Input */}
      <Card>
        <CardHeader>
          <CardTitle>JSON Input</CardTitle>
        </CardHeader>
        <CardContent>
          <textarea
            value={jsonInput}
            onChange={(e) => setJsonInput(e.target.value)}
            className="h-64 w-full resize-none rounded-md border bg-background p-3 font-mono text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            placeholder="Enter your JSON object here..."
          />
          {jsonError && (
            <p className="mt-2 text-sm text-destructive">{jsonError}</p>
          )}
        </CardContent>
      </Card>

      {/* LLM Messages Section */}
      <Card>
        <CardHeader>
          <CardTitle>LLM Messages</CardTitle>
        </CardHeader>
        <CardContent>
          <p className="mb-4 text-sm text-muted-foreground">
            Build your prompt messages below. Use the &quot;Insert
            Variable&quot; button to add JSON paths as template variables.
          </p>
          <LLMMessagesSection jsonData={parsedJson} />
        </CardContent>
      </Card>

      {/* Popover Trigger */}
      <Card>
        <CardHeader>
          <CardTitle>Explore JSON Structure</CardTitle>
        </CardHeader>
        <CardContent>
          <JsonTreePopover
            data={parsedJson || {}}
            onSelect={handlePathSelect}
            trigger={
              <Button disabled={!parsedJson}>
                <Braces className="mr-2 size-4" />
                Open JSON Tree
              </Button>
            }
          />
        </CardContent>
      </Card>
    </div>
  );
};

export default JsonBuilderPage;
