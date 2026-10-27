{{/*
Expand the name of the chart.
*/}}
{{- define "opik.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "opik.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "opik.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "opik.labels" -}}
{{ include "opik.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "opik.clickhouse.labels" -}}
{{ include "opik.clickhouse.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{/*
Selector labels
*/}}
{{- define "opik.selectorLabels" -}}
app.kubernetes.io/name: {{ include "opik.name" $ }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
{{- define "opik.clickhouse.selectorLabels" -}}
app.kubernetes.io/name: {{ include "opik.name" $ }}
app.kubernetes.io/instance: {{ .Release.Name }}
component: clickhouse
# app.kubernetes.io/component: clickhouse
{{- end }}

{{/*
ClickHouse LWD-reclaim merge settings (conf.d/merge_tree.xml), from clickhouse.forceMerge.
*/}}
{{- define "opik.clickhouse.mergeTreeXml" -}}
{{- $fm := .Values.clickhouse.forceMerge -}}
{{- /* A malformed value would render 0, which ClickHouse reads as "off", so fail instead. */ -}}
{{- range $key, $min := dict "minAgeSeconds" 1 "maxBytesToMergeAtMaxSpaceInPool" 1 "numberOfFreeEntriesInPoolToLowerMaxSizeOfMerge" 0 -}}
{{- $v := get $fm $key -}}
{{- if not (or (kindIs "float64" $v) (kindIs "int" $v) (kindIs "int64" $v)) -}}
{{- fail (printf "clickhouse.forceMerge.%s must be a number, got %v" $key $v) -}}
{{- end -}}
{{- if or (ne (float64 $v) (float64 (int64 $v))) (lt (int64 $v) (int64 $min)) -}}
{{- fail (printf "clickhouse.forceMerge.%s must be an integer >= %d, got %v" $key $min $v) -}}
{{- end -}}
{{- end -}}
{{- if not (kindIs "bool" $fm.onPartitionOnly) -}}
{{- fail (printf "clickhouse.forceMerge.onPartitionOnly must be true or false, got %v" $fm.onPartitionOnly) -}}
{{- end -}}
<clickhouse>
  <merge_tree>
    <max_bytes_to_merge_at_max_space_in_pool>{{ int64 $fm.maxBytesToMergeAtMaxSpaceInPool }}</max_bytes_to_merge_at_max_space_in_pool>
    <min_age_to_force_merge_seconds>{{ int64 $fm.minAgeSeconds }}</min_age_to_force_merge_seconds>
    <min_age_to_force_merge_on_partition_only>{{ ternary 1 0 $fm.onPartitionOnly }}</min_age_to_force_merge_on_partition_only>
    <number_of_free_entries_in_pool_to_lower_max_size_of_merge>{{ int64 $fm.numberOfFreeEntriesInPoolToLowerMaxSizeOfMerge }}</number_of_free_entries_in_pool_to_lower_max_size_of_merge>
  </merge_tree>
</clickhouse>
{{- end }}

{{/*
ClickHouse tiered-storage server config (conf.d/storage.xml).

Renders the hot -> cold storage policy per Section 5.3 of the Hyperscale plan.
Helm renders a concrete <endpoint>; ClickHouse macros ({shard}/{replica})
cannot be used here because the S3 disk is read at server start, before macros
bind. Inert until a later, environment-gated migration attaches the
`tiered_replicated` policy to a table.
*/}}
{{- define "opik.clickhouse.storageXml" -}}
{{- $ts := .Values.clickhouse.tieredStorage -}}
{{- $s3 := $ts.cold.s3 -}}
{{- $vn := $ts.cold.cache.volumeName -}}
{{- if not (regexMatch "^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$" $vn) -}}
{{- fail (printf "clickhouse.tieredStorage.cold.cache.volumeName %q must be a DNS label (<=63 chars, lowercase alphanumeric or '-', start with a letter, end alphanumeric)" $vn) -}}
{{- end -}}
{{- if not $s3.endpoint -}}
{{- fail "clickhouse.tieredStorage.cold.s3.endpoint must be set to the cold bucket's S3 URL when tiered storage is enabled" -}}
{{- end -}}
{{- $endpoint := printf "%s/%s/" (trimSuffix "/" $s3.endpoint) (trimSuffix "/" (trimPrefix "/" $s3.prefix)) -}}
<clickhouse>
  <storage_configuration>
    <disks>
      <!-- Hot tier is ClickHouse's built-in `default` disk (the main data path).
           CH 26.x rejects a custom local disk whose <path> equals the server
           data path, so we tune keep_free_space_bytes on `default` directly
           rather than defining a separate <hot> disk. -->
      <default>
        <keep_free_space_bytes>{{ $ts.hot.keepFreeSpaceBytes }}</keep_free_space_bytes>
      </default>
      <cold_s3>
        <type>s3</type>
        <endpoint>{{ $endpoint }}</endpoint>
        {{- if $s3.readOnly }}
        <read_only>true</read_only>
        {{- end }}
        {{- if $s3.useEnvironmentCredentials }}
        <use_environment_credentials>true</use_environment_credentials>
        {{- end }}
        {{- if $s3.region }}
        <region>{{ $s3.region }}</region>
        {{- end }}
        <s3_max_get_rps>{{ $s3.maxGetRps }}</s3_max_get_rps>
        <s3_max_put_rps>{{ $s3.maxPutRps }}</s3_max_put_rps>
      </cold_s3>
      <cold>
        <type>cache</type>
        <disk>cold_s3</disk>
        <path>{{ $ts.cold.cache.path }}/</path>
        <max_size>{{ $ts.cold.cache.maxSize }}</max_size>
        <cache_on_write_operations>0</cache_on_write_operations>
      </cold>
    </disks>
    <policies>
      <tiered_replicated>
        <volumes>
          <!-- Named `default`, not `hot`: ClickHouse rejects a policy switch unless the new
               policy carries a volume of the same name as the old one ("New storage policy
               shall contain volumes of the old storage policy", BAD_ARGUMENTS). Every table
               starts on the built-in `default` policy, whose volume is `default`, so naming
               this one anything else makes the policy unattachable to any existing table. -->
          <default>
            <disk>default</disk>
          </default>
          <cold>
            <disk>cold</disk>
          </cold>
        </volumes>
      </tiered_replicated>
    </policies>
  </storage_configuration>
</clickhouse>
{{- end }}

{{/*
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "opik.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "opik.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "clickhouse.serviceAccountName" -}}
{{- if .Values.clickhouse.serviceAccount.create }}
{{- default (include "opik.fullname" .) .Values.clickhouse.serviceAccount.name }}
{{- else }}
{{- default "default" (include "opik.serviceAccountName" .) }}
{{- end }}
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "clickhouse.backup.serviceAccountName" -}}
{{- if .Values.clickhouse.backup.serviceAccount.create }}
{{- default (include "clickhouse.serviceAccountName" .) .Values.clickhouse.backup.serviceAccount.name }}
{{- else }}
{{- default "default" (include "clickhouse.serviceAccountName" .) }}
{{- end }}
{{- end }}
{{/*
Create the name of the service account to use
*/}}
{{- define "service.serviceAccountName" -}}
{{- if .serviceAccount.create }}
{{- default ( cat .serviceName "-sa" | nospace ) .serviceAccount.name }}
{{- else }}
{{- default "default" .serviceAccount.name }}
{{- end }}
{{- end }}
{{/*
Generate Content-Security-Policy header value from CSP configuration
Usage: {{ include "opik.cspHeaderValue" .Values.component.frontend.contentSecurityPolicy }}
Returns: "default-src 'self'; script-src 'self' 'unsafe-inline'"
Note: Directives are sorted alphabetically to ensure deterministic output
*/}}
{{- define "opik.cspHeaderValue" -}}
{{- if . -}}
{{-   $cspParts := list -}}
{{-   $cspMap := . -}}
{{-   $sortedKeys := keys . | sortAlpha -}}
{{-   range $sortedKeys -}}
{{-     $directive := . -}}
{{-     $sources := index $cspMap $directive -}}
{{-     $cspParts = append $cspParts (printf "%s %s" $directive (join " " $sources)) -}}
{{-   end -}}
{{-   join "; " $cspParts -}}
{{- end -}}
{{- end -}}

{{/*
Renders a container probe (liveness / readiness / startup) from a probe spec.
Supports two shapes for the same spec:
  1. Simplified path/port: BOTH `path` and `port` must be set. The remaining
     probe fields (timeoutSeconds, periodSeconds, initialDelaySeconds,
     successThreshold, failureThreshold) are each honored if the caller sets
     them, otherwise fall back to defaults (2 / 10 / 0 / 1 / 2). This is a
     common case for HTTP probes.
     (Setting only one of `path`/`port` is a misconfiguration — provide both,
     or use a full probe spec below.)
  2. Full definition: any other spec is emitted verbatim, so values.yaml can
     define httpGet/exec/tcpSocket plus any probe timing fields directly.
Usage:
{{- include "opik.probe" $value.livenessProbe | nindent 12 }}
*/}}
{{- define "opik.probe" -}}
{{- if and .path .port }}
{{- /* Backward compatibility: simplified path/port definition */}}
httpGet:
  path: {{ .path }}
  port: {{ .port }}
  httpHeaders:
    - name: Accept
      value: application/json
timeoutSeconds: {{ .timeoutSeconds | default 2 }}
periodSeconds: {{ .periodSeconds | default 10 }}
initialDelaySeconds: {{ .initialDelaySeconds | default 0 }}
successThreshold: {{ .successThreshold | default 1 }}
failureThreshold: {{ .failureThreshold | default 2 }}
{{- else }}
{{- /* Full probe definition from values.yaml */}}
{{- toYaml . }}
{{- end }}
{{- end -}}

{{/*
Renders a value that contains template perhaps with scope if the scope is present.
Usage:
{{ include "common.tplvalues.render" (dict "value" .Values.path.to.value "context" $) }}
*/}}
{{- define "common.tplvalues.render" -}}
{{- $value := typeIs "string" .value | ternary .value (.value | toYaml) }}
{{- if contains "{{" (toJson .value) }}
  {{- if .scope }}
      {{- tpl (cat "{{- with $.RelativeScope -}}" $value "{{- end }}") (merge (dict "RelativeScope" .scope) .context) }}
  {{- else }}
    {{- tpl $value .context }}
  {{- end }}
{{- else }}
    {{- $value }}
{{- end }}
{{- end -}}

{{/*
ALERTS_EVENTBRIDGE_* backend env from alerts.awsEventBridge, as a YAML map; empty when disabled.
Validates like the backend's startup checks, so a bad value fails the render instead of crash-looping
the pod. REGION is left out when empty so the backend resolves it from the ARN or the pod's region.
*/}}
{{- define "opik.alerts.awsEventBridgeEnv" -}}
{{- $eb := (.Values.alerts | default dict).awsEventBridge | default dict -}}
{{- $env := dict -}}
{{- if $eb.enabled -}}
{{- if not (kindIs "bool" $eb.enabled) -}}
{{- fail (printf "alerts.awsEventBridge.enabled must be true or false, got %v" $eb.enabled) -}}
{{- end -}}
{{- $bus := required "alerts.awsEventBridge.eventBus is required when alerts.awsEventBridge.enabled is true" $eb.eventBus | toString -}}
{{- $regionPattern := "[a-z]{2,4}(-[a-z]+)+-[0-9]+" -}}
{{- $namePattern := "[.\\-_A-Za-z0-9]{1,256}" -}}
{{- $arnPattern := printf "^arn:aws[a-z-]*:events:(%s):[0-9]{12}:event-bus/%s$" $regionPattern $namePattern -}}
{{- $isArn := regexMatch $arnPattern $bus -}}
{{- /* Custom bus names can't contain '/'; only partner buses can, and they aren't PutEvents targets. */ -}}
{{- if not (or $isArn (regexMatch (printf "^%s$" $namePattern) $bus)) -}}
{{- fail (printf "alerts.awsEventBridge.eventBus must be a bus name or an event-bus ARN, got %q" $bus) -}}
{{- end -}}
{{- $_ := set $env "ALERTS_EVENTBRIDGE_ENABLED" "true" -}}
{{- $_ := set $env "ALERTS_EVENTBRIDGE_EVENT_BUS" $bus -}}
{{- with $eb.region -}}
{{- $region := toString . -}}
{{- if not (regexMatch (printf "^%s$" $regionPattern) $region) -}}
{{- fail (printf "alerts.awsEventBridge.region must be an AWS region such as us-east-1, got %q" $region) -}}
{{- end -}}
{{- if and $isArn (ne $region (index (splitList ":" $bus) 3)) -}}
{{- fail (printf "alerts.awsEventBridge.region %q does not match the region of eventBus %q" $region $bus) -}}
{{- end -}}
{{- $_ := set $env "ALERTS_EVENTBRIDGE_REGION" $region -}}
{{- end -}}
{{- end -}}
{{- toYaml $env -}}
{{- end }}
