"""
Common test data constants for attachment tests.

This module contains base64-encoded and binary test data for various file formats
used across attachment decoder tests.
"""

import base64


# PNG Test Data
# 1x1 transparent PNG image
PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

# JPEG Test Data
# Minimal valid JPEG image (1x1 red pixel)
JPEG_BASE64 = "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/2wBDAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQH/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwCRAP/Z"

# PDF Test Data
# Minimal valid PDF document with "Hello World"
PDF_BYTES: bytes = b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n1 0 obj\n<<\n/Type /Catalog\n/Pages 2 0 R\n>>\nendobj\n2 0 obj\n<<\n/Type /Pages\n/Kids [3 0 R]\n/Count 1\n>>\nendobj\n3 0 obj\n<<\n/Type /Page\n/Parent 2 0 R\n/Resources <<\n/Font <<\n/F1 4 0 R\n>>\n>>\n/MediaBox [0 0 612 792]\n/Contents 5 0 R\n>>\nendobj\n4 0 obj\n<<\n/Type /Font\n/Subtype /Type1\n/BaseFont /Helvetica\n>>\nendobj\n5 0 obj\n<<\n/Length 44\n>>\nstream\nBT\n/F1 24 Tf\n100 700 Td\n(Hello World) Tj\nET\nendstream\nendobj\nxref\n0 6\n0000000000 65535 f\n0000000015 00000 n\n0000000074 00000 n\n0000000131 00000 n\n0000000277 00000 n\n0000000356 00000 n\ntrailer\n<<\n/Size 6\n/Root 1 0 R\n>>\nstartxref\n448\n%%EOF"
PDF_BASE64 = base64.b64encode(PDF_BYTES).decode("utf-8")

# GIF Test Data
# Minimal GIF89a image
GIF89_BYTES: bytes = (
    b"GIF89a\x01\x00\x01\x00\x00\xff\x00,\x00\x00\x00\x00\x01\x00\x01\x00\x00\x02\x00;"
)
GIF89_BASE64 = base64.b64encode(GIF89_BYTES).decode("utf-8")

# WebP Test Data
# Minimal WebP image
WEBP_BYTES: bytes = b"RIFF\x1a\x00\x00\x00WEBPVP8 \x0e\x00\x00\x000\x01\x00\x9d\x01*\x01\x00\x01\x00\x00\x00\x00\x00"
WEBP_BASE64 = base64.b64encode(WEBP_BYTES).decode("utf-8")

# SVG Test Data
# Simple SVG image with a red circle
SVG_BYTES: bytes = b'<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><circle cx="50" cy="50" r="40" fill="red"/></svg>'
SVG_BASE64 = base64.b64encode(SVG_BYTES).decode("utf-8")

# JSON Test Data
JSON_BYTES: bytes = b'{"key": "value", "number": 123, "array": [1, 2, 3]}'
JSON_BASE64 = base64.b64encode(JSON_BYTES).decode("utf-8")

# Plain Text Test Data
PLAIN_TEXT_BYTES: bytes = b"This is just plain text without special markers"
PLAIN_TEXT_BASE64 = base64.b64encode(PLAIN_TEXT_BYTES).decode("utf-8")

# Random Binary Data (octet-stream)
RANDOM_BINARY_BYTES: bytes = b"\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a"
RANDOM_BINARY_BASE64 = base64.b64encode(RANDOM_BINARY_BYTES).decode("utf-8")
