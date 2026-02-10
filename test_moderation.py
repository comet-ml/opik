#!/usr/bin/env python3
"""Test script for content moderation agent API."""

import requests
import json

API_URL = "http://localhost:8001/chat"

EXAMPLES = [
    ("Dark humor", "My code is so bad it should be considered a crime against humanity."),
    ("Scam attempt", "Congratulations! You've won $1,000,000. Send us $50 for processing fees to claim your prize."),
    ("Sarcasm", "Oh wow, what a GENIUS idea. I'm sure nobody has ever thought of that before."),
    ("Gaming trash talk", "I'm going to destroy you in the next round. You're dead meat."),
    ("Venting frustration", "I swear this app is trying to kill me. I've restarted it 50 times today."),
    ("Quoting someone", "He literally said 'you're worthless' to me in the meeting. Can you believe that?"),
    ("Health discussion", "I've been feeling really down lately. Some days I just don't want to get out of bed."),
    ("Political debate", "Anyone who supports that policy is either ignorant or doesn't care about working families."),
    ("Tech support rant", "Your security team is a joke. I found three vulnerabilities in five minutes."),
    ("Relationship advice", "Honestly, you should just ghost him. He doesn't deserve an explanation."),
]

def test(name: str, content: str):
    print(f"\n{'='*60}")
    print(f"TEST: {name}")
    print(f"Content: {content[:60]}{'...' if len(content) > 60 else ''}")
    print("-" * 60)

    resp = requests.post(API_URL, json={"message": content})
    if resp.status_code != 200:
        print(f"Error: {resp.status_code}")
        print(f"Body: {resp.text[:500]}")
        return
    result = resp.json()["response"]

    print(f"Decision: {result['decision']}")
    print(f"Overall:  {result['scores'].get('overall', 'N/A')}")
    print(f"Items:    {len(result['risk_items'])}")
    if result['risk_items']:
        for item in result['risk_items'][:2]:
            print(f"  - [{item['category']}] \"{item['span'][:40]}...\"")

if __name__ == "__main__":
    print("Testing Content Moderation API")
    for name, content in EXAMPLES:
        test(name, content)
    print(f"\n{'='*60}")
    print("Done!")
