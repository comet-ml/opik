import opik.guardrails.guards.topic as topic
import opik.guardrails.schemas as schemas


def test_topic__allowed_topics():
    guard = topic.Topic(allowed_topics=["education", "technology"], threshold=0.5)

    configs = guard.get_validation_configs()

    assert len(configs) == 1
    assert configs[0] == {
        "type": schemas.ValidationType.TOPIC,
        "config": {
            "topics": ["education", "technology"],
            "threshold": 0.5,
            "mode": "allow",
        },
    }


def test_topic__restricted_topics():
    guard = topic.Topic(restricted_topics=["finance", "gambling"], threshold=0.7)

    configs = guard.get_validation_configs()

    assert len(configs) == 1
    assert configs[0] == {
        "type": schemas.ValidationType.TOPIC,
        "config": {
            "topics": ["finance", "gambling"],
            "threshold": 0.7,
            "mode": "restrict",
        },
    }


def test_topic__allowed_and_restricted_topics_set():
    guard = topic.Topic(allowed_topics=["education"], restricted_topics=["finance"])

    configs = guard.get_validation_configs()

    assert len(configs) == 2
    assert configs == [
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {
                "topics": ["education"],
                "threshold": 0.5,
                "mode": "allow",
            },
        },
        {
            "type": schemas.ValidationType.TOPIC,
            "config": {
                "topics": ["finance"],
                "threshold": 0.5,
                "mode": "restrict",
            },
        },
    ]
