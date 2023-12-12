from comet_ml import api

Duration = lambda: api.Metric("duration")
Timestamp = lambda: api.Metadata("start_server_timestamp")
TraceMetadata = api.Parameter
TraceDetail = api.Metadata
Other = api.Other
