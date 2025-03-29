import enum


class PromptType(str, enum.Enum):
    MUSTACHE = "mustache"
    JINJA2 = "jinja2"
