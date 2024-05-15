# -*- coding: utf-8 -*-
# *******************************************************
#   ____                     _               _
#  / ___|___  _ __ ___   ___| |_   _ __ ___ | |
# | |   / _ \| '_ ` _ \ / _ \ __| | '_ ` _ \| |
# | |__| (_) | | | | | |  __/ |_ _| | | | | | |
#  \____\___/|_| |_| |_|\___|\__(_)_| |_| |_|_|
#
#  Sign up for free at https://www.comet.com
#  Copyright (C) 2015-2023 Comet ML INC
#  This source code is licensed under the MIT license found in the
#  LICENSE file in the root directory of this package.
# *******************************************************

# vendored from and tested in comet-ml

from urllib.parse import urljoin, urlparse, urlunparse


def sanitize_url(url: str) -> str:
    """Sanitize an URL, checking that it is a valid URL and ensure it contains an ending slash /"""
    parts = urlparse(url)
    scheme, netloc, path, params, query, fragment = parts

    # TODO: Raise an exception if params, query and fragment are not empty?

    # Ensure the leading slash
    if path and not path.endswith("/"):
        path = path + "/"
    elif not path and not netloc.endswith("/"):
        netloc = netloc + "/"

    return urlunparse((scheme, netloc, path, params, query, fragment))


def url_join(base: str, *parts: str) -> str:
    """Given a base and url parts (for example [workspace, project, id]) returns a full URL"""
    # TODO: Enforce base to have a scheme and netloc?
    result = base

    for part in parts[:-1]:
        if not part.endswith("/"):
            raise ValueError("Intermediary part not ending with /")

        result = urljoin(result, part)

    result = urljoin(result, parts[-1])

    return result


def get_root_url(url: str) -> str:
    """Remove the path, params, query and fragment from a given URL"""
    parts = urlparse(url)
    scheme, netloc, path, params, query, fragment = parts

    return urlunparse((scheme, netloc, "", "", "", ""))


def experiment_to_project_url(experiment_url: str) -> str:
    return "/".join(experiment_url.split("/")[:-1])
