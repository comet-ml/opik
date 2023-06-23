import atexit
from . import summary

SUMMARY = summary.Summary()

atexit.register(SUMMARY.print)