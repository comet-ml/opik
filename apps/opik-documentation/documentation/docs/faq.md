---
sidebar_position: 100
sidebar_label: FAQ
---

# FAQ

These FAQs are a collection of the most common questions that we've received from our users. If you have any other questions, please open an [issue on GitHub](https://github.com/comet-opik/opik/issues).

## General

### Can I use Opik to monitor my LLM application in production?

Yes, Opik has been designed from the ground up to be used to monitor production applications. If you are self-hosting the
Opik platform, we recommend using the [Kuberneters deployment](/self-host/overview.md) option to ensure that Opik can scale as needed.

## Opik Cloud

### Are there are rate limits on Opik Cloud?

Yes, in order to ensure all users have a good experience we have implemented rate limits. Each user is limited to `10,000` events per minute, an event is a trace, span, feedback score, dataset item, experiment item, etc. If you need to increase this limit please reach out to us on [Slack](https://chat.comet.com).
