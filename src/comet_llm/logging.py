import logging
import sys

def setup():
    root = logging.getLogger("comet_llm")
    root.setLevel(logging.INFO)

    console_handler = logging.StreamHandler(sys.stdout)
    root.addHandler(console_handler)
