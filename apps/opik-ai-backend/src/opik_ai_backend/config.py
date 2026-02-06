from typing import Optional

from pydantic import Field
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """Centralized configuration for the trace analyzer application."""

    # Server configuration
    port: int = Field(default=8081, description="Server port", alias="PORT")

    # Opik backend URL for agent data retrieval
    agent_opik_url: str = Field(
        description="Opik backend URL for agent data retrieval",
        alias="AGENT_OPIK_URL",
    )

    # Internal logging configuration (Context B)
    # Used for OpikAssist's own observability (OpikTracer, @opik.track)
    # If opik_internal_url is None, internal logging is disabled
    opik_internal_url: Optional[str] = Field(
        default=None,
        description="Opik URL for internal logging (if None, logging disabled)",
        alias="OPIK_INTERNAL_URL",
    )
    opik_internal_api_key: Optional[str] = Field(
        default=None,
        description="Opik API key for internal logging",
        alias="OPIK_INTERNAL_API_KEY",
    )
    opik_internal_workspace: Optional[str] = Field(
        default=None,
        description="Opik workspace for internal logging",
        alias="OPIK_INTERNAL_WORKSPACE",
    )
    opik_internal_project: str = Field(
        default="opik-assist",
        description="Opik project name for internal logging",
        alias="OPIK_INTERNAL_PROJECT",
    )

    # Database configuration
    session_service_uri: Optional[str] = Field(
        default=None,
        description="Database URI for session storage",
        alias="SESSION_SERVICE_URI",
    )

    # CORS configuration
    allowed_origins: list[str] = Field(
        default=[
            "http://localhost",
            "http://localhost:8080",
            "https://www.comet.com",
            "http://localhost:5174",
        ],
        description="Allowed CORS origins",
    )

    # Sentry configuration
    sentry_dsn: Optional[str] = Field(
        default=None,
        description="Sentry DSN for error monitoring and performance tracking",
        alias="SENTRY_DSN",
    )
    sentry_environment: str = Field(
        default="development",
        description="Environment name for Sentry (e.g., development, staging, production)",
        alias="SENTRY_ENVIRONMENT",
    )
    sentry_traces_sample_rate: float = Field(
        default=1.0,
        description="Sample rate for performance traces (0.0 to 1.0)",
        alias="SENTRY_TRACES_SAMPLE_RATE",
    )
    sentry_profiles_sample_rate: float = Field(
        default=1.0,
        description="Sample rate for profiling (0.0 to 1.0)",
        alias="SENTRY_PROFILES_SAMPLE_RATE",
    )
    sentry_send_default_pii: bool = Field(
        default=False,
        description="Whether to send personally identifiable information to Sentry",
        alias="SENTRY_SEND_DEFAULT_PII",
    )

    # Segment configuration
    segment_write_key: Optional[str] = Field(
        default=None,
        description="Segment write key for analytics tracking",
        alias="SEGMENT_WRITE_KEY",
    )
    segment_environment: str = Field(
        default="development",
        description="Environment name for Segment (e.g., development, staging, production)",
        alias="SEGMENT_ENVIRONMENT",
    )

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


# Global settings instance
settings = Settings()
