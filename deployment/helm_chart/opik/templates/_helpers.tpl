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
      <hot>
        <keep_free_space_bytes>{{ $ts.hot.keepFreeSpaceBytes }}</keep_free_space_bytes>
      </hot>
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
          <hot>
            <disk>hot</disk>
          </hot>
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
