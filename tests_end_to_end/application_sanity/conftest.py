import pytest
import os
import opik
import yaml
from opik.configurator.configure import configure
from opik import opik_context, track


@pytest.fixture(scope='session', autouse=True)
def config():
    curr_dir = os.path.dirname(__file__)
    config_path = os.path.join(curr_dir, 'sanity_config.yaml')

    with open(config_path, 'r') as f:
        conf = yaml.safe_load(f)
    return conf


@pytest.fixture(scope='session', autouse=True)
def configure_local(config):
    configure(use_local=True)
    os.environ['OPIK_PROJECT_NAME'] = config['project']['name']


@pytest.fixture(scope='session', autouse=True)
def client(config):
    return opik.Opik(project_name=config['project']['name'])


@pytest.fixture(scope='function')
def log_traces_and_spans_low_level(client, config):
    """
    Log 5 traces with spans and subspans using the low level Opik client
    Each should have their own names, tags, metadata and feedback scores to test integrity of data transmitted
    """

    trace_config = {
        'count': config['traces']['client']['count'],
        'prefix': config['traces']['client']['prefix'],
        'tags': config['traces']['client']['tags'],
        'metadata': config['traces']['client']['metadata'],
        'feedback_scores': [{'name': key, 'value': value} for key, value in config['traces']['client']['feedback-scores'].items()]
    }

    span_config = {
        'count': config['spans']['client']['count'],
        'prefix': config['spans']['client']['prefix'],
        'tags': config['spans']['client']['tags'],
        'metadata': config['spans']['client']['metadata'],
        'feedback_scores': [{'name': key, 'value': value} for key, value in config['spans']['client']['feedback-scores'].items()]
    }

    for trace_index in range(trace_config['count']):
        client_trace = client.trace(
            name=trace_config['prefix'] + str(trace_index),
            input=f'input-{trace_index}',
            output=f'output-{trace_index}',
            tags=trace_config['tags'],
            metadata=trace_config['metadata'],
            feedback_scores=trace_config['feedback_scores']
        )
        for span_index in range(span_config['count']):
            client_span = client_trace.span(
                name=span_config['prefix'] + str(span_index),
                input=f'input-{span_index}',
                output=f'output-{span_index}',
                tags=span_config['tags'],
                metadata=span_config['metadata']
            )
            for score in span_config['feedback_scores']:
                client_span.log_feedback_score(name=score['name'], value=score['value'])


@pytest.fixture(scope='function')
def log_traces_and_spans_decorator(config):
    """
    Log 5 traces with spans and subspans using the low level Opik client
    Each should have their own names, tags, metadata and feedback scores to test integrity of data transmitted
    """
    
    trace_config = {
        'count': config['traces']['decorator']['count'],
        'prefix': config['traces']['decorator']['prefix'],
        'tags': config['traces']['decorator']['tags'],
        'metadata': config['traces']['decorator']['metadata'],
        'feedback_scores': [{'name': key, 'value': value} for key, value in config['traces']['decorator']['feedback-scores'].items()]
    }

    span_config = {
        'count': config['spans']['decorator']['count'],
        'prefix': config['spans']['decorator']['prefix'],
        'tags': config['spans']['decorator']['tags'],
        'metadata': config['spans']['decorator']['metadata'],
        'feedback_scores': [{'name': key, 'value': value} for key, value in config['spans']['decorator']['feedback-scores'].items()]
    }

    @track()
    def make_span(x):
        opik_context.update_current_span(
            name=span_config['prefix'] + str(x),
            input=f'input-{x}',
            metadata=span_config['metadata'],
            tags=span_config['tags'],
            feedback_scores=span_config['feedback_scores']
        )
        return f'output-{x}'
    
    @track()
    def make_trace(x):
        for spans_no in range(span_config['count']):
            make_span(spans_no)

        opik_context.update_current_trace(
            name=trace_config['prefix'] + str(x),
            input=f'input-{x}',
            metadata=trace_config['metadata'],
            tags=trace_config['tags'],
            feedback_scores=trace_config['feedback_scores']
        )
        return f'output-{x}'
    
    for x in range(trace_config['count']):
        make_trace(x)