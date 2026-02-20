# Workspace Permissions Table

| Permission group | Permission | Permission tech name | Owner | Write | Annotator | Read |
|-----------------|------------|---------------------|-------|-------|-----------|------|
| Admin | Configure workspace settings | settings_configure | Yes | No | No | No |
| | Invite users<br>(org setting to disable it and limit to owners) | user_invite | Yes | Yes | No | No |
| | Update user role | user_role_update | Yes | No | No | No |
| | Delete workspaces | workspace_delete | Yes | No | No | No |
| | Define AI providers<br>(org setting to disable it) | ai_provider_define | Yes | Yes | No | No |
| Experiment Management | Change project settings | project_settings_change | Yes | No | No | No |
| | Approve and manage models | model_approve | Yes | No | No | No |
| Opik - Observability | Create projects | project_create | Yes | Yes | No | No |
| | View project data | project_data_view | Yes | Yes | Yes | Yes |
| | Log trace, span or thread | trace_log<br>span_log<br>thread_log | Yes | Yes | No | No |
| | Write comments | comment_write | Yes | Yes | Yes | No |
| | Annotate trace, span or thread | trace_annotate<br>span_annotate<br>thread_annotate | Yes | Yes | Yes | No |
| | Tag trace | trace_tag | Yes | Yes | Yes | No |
| | Define online evaluation rule | evaluation_rule_online_define | Yes | Yes | No | No |
| | Define alert | alert_define | Yes | Yes | No | No |
| | Create annotation queue | annotation_queue_create | Yes | Yes | No | No |
| Opik - Dashboards | View dashboard | dashboard_view | Yes | Yes | No | Yes |
| | Edit dashboards | dashboard_edit | Yes | Yes | No | No |
| | Create dashboards | dashboard_create | Yes | Yes | No | No |
| Opik - Experiments | View experiments | experiment_view | Yes | Yes | No | Yes |
| | Create experiment | experiment_create | Yes | Yes | No | No |
| Opik - Datasets | View datasets | dataset_view | Yes | Yes | No | Yes |
| | Edit datasets | dataset_edit | Yes | Yes | No | No |
| | Delete datasets | dataset_delete | Yes | Yes | No | No |
| Opik - Annotation queues | View annotation queues | annotation_queue_view | Yes | Yes | Yes | Yes |
| | Annotate trace, span or thread | annotation_queue_annotate | Yes | Yes | Yes | Yes |
| | Edit annotation queue permission | annotation_queue_permission_edit | Yes | Yes | No | No |
| | Delete annotation queue | annotation_queue_delete | Yes | Yes | No | No |
| | Export annotation queue results | annotation_queue_results_export | Yes | Yes | Yes | Yes |
| Opik - Prompt library | View prompts | prompt_view | Yes | Yes | No | Yes |
| | Create prompt | prompt_create | Yes | Yes | No | No |
| | Edit prompt | prompt_edit | Yes | Yes | No | No |
| | Delete prompt | prompt_delete | Yes | Yes | No | No |
| Opik - Playground | Use playground | playground_use | Yes | Yes | No | No |
| Opik - Optimization | View Optimization runs | optimization_run_view | Yes | Yes | Yes | Yes |
| | Delete Optimization runs | optimization_run_delete | Yes | Yes | No | No |
| | Use optimization studio | optimization_studio_use | Yes | Yes | No | No |
