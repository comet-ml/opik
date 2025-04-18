ONE_KBYTE = float(1024)
ONE_MBYTE = float(1024 * 1024)
ONE_GBYTE = float(1024 * 1024 * 1024)


def format_bytes(size: float) -> str:
    """
    Given a size in bytes, return a sort string representation.
    """
    if size >= ONE_GBYTE:
        return "%.2f %s" % (size / ONE_GBYTE, "GB")
    elif size >= ONE_MBYTE:
        return "%.2f %s" % (size / ONE_MBYTE, "MB")
    elif size >= ONE_KBYTE:
        return "%.2f %s" % (size / ONE_KBYTE, "KB")
    else:
        return "%d %s" % (size, "bytes")
