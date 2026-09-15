import mock
import pytest
import torch

from opik_guardrails.services.validators.custom_classifier import validator
from opik_guardrails import schemas


def test_validate_missing_model_raises_clear_error(tmp_path):
    custom_validator = validator.CustomClassifierValidator(adapters_dir=str(tmp_path))
    config = schemas.CustomClassifierValidationConfig(
        model_name="does-not-exist", threshold=0.5
    )

    with pytest.raises(ValueError) as exc_info:
        custom_validator.validate("some text", config)

    message = str(exc_info.value)
    assert "does-not-exist" in message
    assert str(tmp_path) in message


class _FakeInputs(dict):
    def to(self, device):
        return self


def _validator_with_fake_model(true_logit, false_logit):
    custom_validator = validator.CustomClassifierValidator(adapters_dir="/unused")

    true_id, false_id = 5, 6
    logits = torch.zeros(1, 3, 10)
    logits[0, -1, true_id] = true_logit
    logits[0, -1, false_id] = false_logit

    fake_tokenizer = mock.Mock(
        return_value=_FakeInputs(
            input_ids=torch.tensor([[1, 2, 3]]),
            attention_mask=torch.tensor([[1, 1, 1]]),
        )
    )
    fake_model = mock.Mock(return_value=mock.Mock(logits=logits))
    template = "Determine whether it is bad.\n\nString:\n{text}\n\nAnswer:"

    fake_base = mock.Mock()
    fake_base.tokenizer = fake_tokenizer
    fake_base.true_id = true_id
    fake_base.false_id = false_id
    fake_base.device = "cpu"
    fake_base.activate.return_value = fake_model

    base_model = "fake/base"
    custom_validator._bases[base_model] = fake_base
    custom_validator._adapters["toxicity-v1"] = (base_model, template)
    return custom_validator


def test_validate_positive_fails():
    custom_validator = _validator_with_fake_model(true_logit=10.0, false_logit=0.0)
    config = schemas.CustomClassifierValidationConfig(
        model_name="toxicity-v1", threshold=0.5
    )

    result = custom_validator.validate("you are terrible", config)

    assert result.type == schemas.ValidationType.CUSTOM_CLASSIFIER
    assert result.validation_passed is False
    assert result.validation_details.model_name == "toxicity-v1"
    assert result.validation_details.score > 0.9


def test_validate_negative_passes():
    custom_validator = _validator_with_fake_model(true_logit=0.0, false_logit=10.0)
    config = schemas.CustomClassifierValidationConfig(
        model_name="toxicity-v1", threshold=0.5
    )

    result = custom_validator.validate("have a nice day", config)

    assert result.validation_passed is True
    assert result.validation_details.score < 0.1
