# File: password_generator.py
# Author: Dheeraj (Hacktoberfest 2025)
# Description: Generates a secure random password.

import string
import random

def generate_password(length=12):
    chars = string.ascii_letters + string.digits + string.punctuation
    password = ''.join(random.choice(chars) for _ in range(length))
    return password

if __name__ == "__main__":
    n = int(input("Enter password length: "))
    print("Generated Password:", generate_password(n))
