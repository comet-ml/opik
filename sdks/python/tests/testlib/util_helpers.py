import random
import string


def random_string(char_count: int = 8):
    return "".join(random.choices(string.ascii_letters + string.digits, k=char_count))
