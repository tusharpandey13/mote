"""Writes a Preferences DataStore file (androidx PreferenceMap proto) with string values.

usage: python3 -I prefs_pb.py key=value [key=value ...] > mote.preferences_pb
"""
import sys


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(tag, payload):
    return bytes([tag]) + varint(len(payload)) + payload


def entry(key, value):
    v = field(0x2A, value.encode())  # Value.string = 5
    return field(0x0A, field(0x0A, key.encode()) + field(0x12, v))  # PreferenceMap.preferences = 1


sys.stdout.buffer.write(b"".join(entry(*a.split("=", 1)) for a in sys.argv[1:]))
