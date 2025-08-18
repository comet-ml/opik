import logging
import json
from typing import Any, Dict, List, Optional

from ...rest_api import client as rest_api_client
from ...rest_api.types import prompt_version_detail
from ..opik_query_language import OpikQueryLanguage
from .types import PromptType

LOGGER = logging.getLogger(__name__)


_OPERATOR_MAP = {
    "=": "EQUAL",
    "!=": "NOT_EQUAL",
    "contains": "CONTAINS",
    "not_contains": "NOT_CONTAINS",
    "starts_with": "STARTS_WITH",
    "ends_with": "ENDS_WITH",
    ">": "GREATER_THAN",
    "<": "LESS_THAN",
    ">=": "GREATER_THAN_EQUAL",
    "<=": "LESS_THAN_EQUAL",
}


def _to_backend_filters(filters: Optional[str]) -> Optional[str]:
    if filters is None or filters.strip() == "":
        return None

    expressions = OpikQueryLanguage(filters).get_filter_expressions()
    if not expressions:
        return None

    converted: List[Dict[str, Any]] = []
    for expr in expressions:
        field = expr.get("field")
        operator = expr.get("operator")
        value = expr.get("value")

        if not field or not operator:
            continue

        backend_field = field.upper()
        backend_operator = _OPERATOR_MAP.get(operator)
        if backend_operator is None:
            continue

        converted.append({
            "field": backend_field,
            "operator": backend_operator,
            "value": value,
        })

    if not converted:
        return None

    return json.dumps(converted)


def _matches_expression(prompt_public: Any, expr: Dict[str, Any]) -> bool:
    field = expr.get("field")
    operator = expr.get("operator")
    value = expr.get("value")

    # Map to PromptPublic attributes
    if field == "name":
        name = getattr(prompt_public, "name", None) or ""
        if operator == "starts_with":
            return name.startswith(value)
        if operator == "ends_with":
            return name.endswith(value)
        if operator == "=":
            return name == value
        if operator == "!=":
            return name != value
        return False

    if field == "id":
        pid = getattr(prompt_public, "id", None)
        if operator == "=":
            return pid == value
        if operator == "!=":
            return pid != value
        return False

    if field == "tags":
        tags = getattr(prompt_public, "tags", None) or []
        if operator == "contains":
            return value in tags
        if operator == "not_contains":
            return value not in tags
        return False

    if field == "version_count":
        vc = getattr(prompt_public, "version_count", None)
        if vc is None:
            return False
        try:
            num = float(value)
        except Exception:
            return False
        if operator == ">=":
            return vc >= num
        if operator == "<=":
            return vc <= num
        return False

    if field == "created_by":
        cb = getattr(prompt_public, "created_by", None) or ""
        if operator == "=":
            return cb == value
        if operator == "starts_with":
            return cb.startswith(value)
        return False

    if field == "last_updated_by":
        lub = getattr(prompt_public, "last_updated_by", None) or ""
        if operator == "!=":
            return lub != value
        if operator == "contains":
            return value in lub
        return False

    if field in ("created_at", "last_updated_at"):
        # For simplicity, skip strict datetime comparisons in client-side path
        return True

    return True


def _apply_client_side_filters(items: List[Any], filters: Optional[str]) -> List[Any]:
    if not filters:
        return items
    expressions = OpikQueryLanguage(filters).get_filter_expressions()
    if not expressions:
        return items

    result = []
    for item in items:
        if all(_matches_expression(item, expr) for expr in expressions):
            result.append(item)
    return result


class PromptClient:
    def __init__(self, client: rest_api_client.OpikApi):
        self._rest_client = client

    def create_prompt(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]],
        type: PromptType = PromptType.MUSTACHE,
    ) -> prompt_version_detail.PromptVersionDetail:
        """
        Creates the prompt detail for the given prompt name and template.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.

        Returns:
        - A Prompt object for the provided prompt name and template.
        """
        prompt_version = self._get_latest_version(name)

        if (
            prompt_version is None
            or prompt_version.template != prompt
            or prompt_version.metadata != metadata
            or (prompt_version.type is not None and prompt_version.type != type.value)
        ):
            prompt_version = self._create_new_version(
                name=name, prompt=prompt, type=type, metadata=metadata
            )

        return prompt_version

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        type: PromptType,
        metadata: Optional[Dict[str, Any]],
    ) -> prompt_version_detail.PromptVersionDetail:
        """
        Creates a new version of the prompt.

        Parameters:
        - name: The name of the prompt.
        - prompt: The template content for the prompt.
        - type: The type of the prompt.
        - metadata: Optional metadata to be included in the prompt.

        Returns:
        - A PromptVersionDetail object for the new version.
        """
        # Use the REST client's create_prompt method directly
        self._rest_client.prompts.create_prompt(
            name=name,
            template=prompt,
            type=type.value,  # REST client expects PromptWriteType (string literal is acceptable)
            metadata=metadata,
        )

        # Get the created prompt using get_prompts with name filter
        prompts_page = self._rest_client.prompts.get_prompts(name=name, size=1)
        if prompts_page.content:
            prompt_public = prompts_page.content[0]
            # Now get the actual version details using the prompt ID
            versions_page = self._rest_client.prompts.get_prompt_versions(id=prompt_public.id, size=1)
            if versions_page.content:
                return versions_page.content[0]

        raise ValueError(f"Could not retrieve created prompt version for name: {name}")

    def _get_latest_version(self, name: str) -> Optional[prompt_version_detail.PromptVersionDetail]:
        """
        Gets the latest version of the prompt.

        Parameters:
        - name: The name of the prompt.

        Returns:
        - A PromptVersionDetail object for the latest version, or None if not found.
        """
        try:
            # First get the prompt by name to get its ID
            prompts_page = self._rest_client.prompts.get_prompts(name=name, size=1)
            if not prompts_page.content:
                return None

            prompt_public = prompts_page.content[0]

            # Now get versions for the prompt ID and return the latest one (first in list)
            versions_page = self._rest_client.prompts.get_prompt_versions(id=prompt_public.id, size=1)
            if versions_page.content:
                return versions_page.content[0]

            return None
        except Exception:
            return None

    def get_prompt(
        self, name: str, commit: Optional[str] = None
    ) -> Optional[prompt_version_detail.PromptVersionDetail]:
        """
        Gets the prompt detail for the given prompt name and commit version.

        Parameters:
        - name: The name of the prompt.
        - commit: An optional commit version of the prompt. If not provided, the latest version is retrieved.

        Returns:
        - A PromptVersionDetail object for the specified prompt, or None if not found.
        """
        try:
            prompts_page = self._rest_client.prompts.get_prompts(name=name, size=1)
            if prompts_page.content:
                prompt_public = prompts_page.content[0]
                # For now, ignore commit parameter and return the first match
                # TODO: Implement proper commit version handling
                return prompt_version_detail.PromptVersionDetail(
                    id=prompt_public.id,
                    prompt_id=prompt_public.id,
                    template=prompt_public.template or "",
                    type=prompt_public.type or "mustache",
                    metadata=prompt_public.metadata,
                    commit=commit or "latest",
                    created_at=prompt_public.created_at,
                    created_by=prompt_public.created_by,
                )
            return None
        except Exception:
            return None

    def get_all_prompts(self, name: str) -> List[prompt_version_detail.PromptVersionDetail]:
        """
        Gets all versions of the prompt.

        Parameters:
        - name: The name of the prompt.

        Returns:
        - A list of PromptVersionDetail objects for all versions of the prompt.
        """
        try:
            # First get the prompt by name to get its ID
            prompts_page = self._rest_client.prompts.get_prompts(name=name, size=1)
            if not prompts_page.content:
                return []
            
            prompt_public = prompts_page.content[0]
            # Now get all versions using the prompt ID
            versions_page = self._rest_client.prompts.get_prompt_versions(id=prompt_public.id)
            return versions_page.content
        except Exception:
            return []

    def get_prompts_with_filters(
        self,
        filters: Optional[str] = None,
        name: Optional[str] = None,
        page: int = 1,
        size: int = 100,
        get_latest_versions: bool = True,
    ) -> List[Dict[str, Any]]:
        """
        Get prompts with filtering support.

        Parameters:
        - filters: Filter string using Opik query language (e.g., 'tags contains "production"')
        - name: Filter by prompt name (exact match)
        - page: Page number (1-based)
        - size: Number of results per page
        - get_latest_versions: Whether to include latest version details

        Returns:
        - List of dictionaries containing prompt_public and latest_version
        """
        try:
            # Prefer backend filters when accepted; otherwise, client-side filter
            backend_filters = _to_backend_filters(filters)
            try:
                prompts_page = self._rest_client.prompts.get_prompts(
                    filters=backend_filters,
                    name=name,
                    page=page,
                    size=size,
                )
            except Exception:
                # Fallback: fetch without filters and apply client-side filtering
                prompts_page = self._rest_client.prompts.get_prompts(
                    filters=None,
                    name=name,
                    page=page,
                    size=size,
                )

            items = prompts_page.content or []
            filtered_items = _apply_client_side_filters(items, filters)

            result = []
            for prompt_public in filtered_items:
                latest_version = None
                if get_latest_versions:
                    latest_version = self._get_latest_version(prompt_public.name)

                result.append({
                    "prompt_public": prompt_public,
                    "latest_version": latest_version,
                })

            return result

        except Exception as e:
            LOGGER.error(f"Failed to get prompts: {e}")
            raise
