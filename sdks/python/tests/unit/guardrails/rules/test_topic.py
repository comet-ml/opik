import pytest

import opik.guardrails.rules.topic as topic
import opik.guardrails.schemas as schemas


def test_topic__allowed_topics():
    topic_rule = topic.Topic(allowed_topics=["education", "technology"])

    configs = topic_rule.get_validation_configs()

    assert configs == [
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {"allowed_topics": ["education", "technology"]},
        }
    ]


def test_topic__restricted_topics():
    topic_rule = topic.Topic(restricted_topics=["finance", "gambling"])

    configs = topic_rule.get_validation_configs()

    assert configs == [
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {"restricted_topics": ["finance", "gambling"]},
        }
    ]


def test_topic__allowed_and_restricted_topics_set__one_check_with_both_lists():
    topic_rule = topic.Topic(
        allowed_topics=["education"], restricted_topics=["finance"]
    )

    configs = topic_rule.get_validation_configs()

    assert configs == [
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {
                "allowed_topics": ["education"],
                "restricted_topics": ["finance"],
            },
        }
    ]


@pytest.mark.parametrize(
    "allowed_topics,restricted_topics",
    [(None, None), ([], []), ([], None)],
)
def test_topic__no_topics__raises_value_error(allowed_topics, restricted_topics):
    with pytest.raises(ValueError):
        topic.Topic(allowed_topics=allowed_topics, restricted_topics=restricted_topics)
