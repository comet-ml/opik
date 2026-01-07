// ESNODE | Source Available BUSL-1.1 | Copyright (c) 2024 Estimatedstocks AB
use std::sync::Arc;

use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::{
        sse::{Event, Sse},
        IntoResponse, Response,
    },
    routing::get,
    Json, Router,
};
use tokio::task::JoinHandle;
use tracing::info;

use crate::metrics::MetricsRegistry;
use crate::state::StatusState;

#[derive(Clone)]
pub struct HttpState {
    pub metrics: MetricsRegistry,
    pub healthy: Arc<std::sync::atomic::AtomicBool>,
    pub status: StatusState,
    pub tsdb: Option<std::sync::Arc<crate::tsdb::LocalTsdb>>,
    pub orchestrator: Option<esnode_orchestrator::AppState>,
    pub orchestrator_allow_public: bool,
    pub listen_is_loopback: bool,
    pub orchestrator_token: Option<String>,
}

pub fn build_router(state: HttpState) -> Router {
    let mut router = Router::new()
        .route("/metrics", get(metrics_handler))
        .route("/healthz", get(health_handler))
        .route("/status", get(status_handler))
        .route("/v1/status", get(status_handler))
        .route("/events", get(events_handler))
        .route("/tsdb/export", get(tsdb_export_handler));

    if let Some(orch_state) = &state.orchestrator {
        if state.orchestrator_allow_public || state.listen_is_loopback {
            router = router.nest_service(
                "/orchestrator",
                esnode_orchestrator::routes(esnode_orchestrator::AppState {
                    orchestrator: orch_state.orchestrator.clone(),
                    token: state.orchestrator_token.clone(),
                }),
            );
        } else {
            tracing::warn!(
                "Orchestrator routes are disabled on non-loopback listener; \
                 set orchestrator.allow_public=true to expose /orchestrator/*"
            );
        }
    }

    router.with_state(state)
}

pub async fn serve(addr: &str, router: Router) -> anyhow::Result<JoinHandle<anyhow::Result<()>>> {
    let listener = tokio::net::TcpListener::bind(addr).await?;
    info!("HTTP server listening on {}", addr);
    Ok(tokio::spawn(async move {
        axum::serve(listener, router).await?;
        Ok(())
    }))
}

async fn metrics_handler(State(state): State<HttpState>) -> Result<Response, StatusCode> {
    let encoded = state
        .metrics
        .encode()
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let mut response = Response::new(encoded.into());
    response.headers_mut().insert(
        axum::http::header::CONTENT_TYPE,
        axum::http::HeaderValue::from_static("text/plain; version=0.0.4"),
    );
    Ok(response)
}

async fn health_handler(State(state): State<HttpState>) -> impl IntoResponse {
    let ok = state.healthy.load(std::sync::atomic::Ordering::Relaxed);
    if ok {
        StatusCode::OK
    } else {
        StatusCode::SERVICE_UNAVAILABLE
    }
}

async fn status_handler(State(state): State<HttpState>) -> impl IntoResponse {
    let snapshot = state.status.snapshot();
    Json(snapshot)
}

async fn events_handler(
    State(state): State<HttpState>,
) -> Sse<impl futures::Stream<Item = Result<Event, std::convert::Infallible>>> {
    use futures::StreamExt;
    use tokio_stream::wrappers::IntervalStream;

    let interval = tokio::time::interval(std::time::Duration::from_secs(5));
    let state_clone = state.status;

    let stream = IntervalStream::new(interval).map(move |_| {
        let snap = state_clone.snapshot();
        let payload = serde_json::to_string(&snap).unwrap_or_else(|_| "{}".to_string());
        Ok(Event::default().data(payload))
    });

    Sse::new(stream).keep_alive(axum::response::sse::KeepAlive::default())
}

#[derive(Debug, serde::Deserialize)]
struct ExportQuery {
    from: Option<i64>,
    to: Option<i64>,
    metrics: Option<String>,
}

async fn tsdb_export_handler(
    State(state): State<HttpState>,
    Query(q): Query<ExportQuery>,
) -> impl IntoResponse {
    if state.tsdb.is_none() {
        return (StatusCode::NOT_FOUND, "local TSDB disabled").into_response();
    }
    let tsdb = state.tsdb.clone().unwrap();
    let metrics_filter = q.metrics.map(|s| {
        s.split(',')
            .map(|m| m.trim().to_string())
            .filter(|m| !m.is_empty())
            .collect::<Vec<_>>()
    });
    match tsdb
        .export_lines(q.from, q.to, metrics_filter.as_ref())
        .await
    {
        Ok(lines) => {
            let body = lines.join("\n");
            (
                [
                    (
                        axum::http::header::CONTENT_TYPE,
                        "text/plain; charset=utf-8",
                    ),
                    (
                        axum::http::header::CACHE_CONTROL,
                        "no-store, max-age=0, must-revalidate",
                    ),
                ],
                body,
            )
                .into_response()
        }
        Err(err) => {
            tracing::warn!("tsdb export failed: {:?}", err);
            StatusCode::INTERNAL_SERVER_ERROR.into_response()
        }
    }
}
