"""Writes a Preferences DataStore file (androidx PreferenceMap proto).

usage: python3 -I prefs_pb.py key=value [key:bool=true] [key:int=3] [key:float=1.5] ... > mote.preferences_pb
Values are strings unless the key has a :bool, :int or :float suffix.
"""
import struct
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


def value(kind, raw):
    if kind == "bool":  # Value.boolean = 1 (varint)
        return b"\x08" + varint(1 if raw.lower() in ("1", "true", "yes") else 0)
    if kind == "int":  # Value.integer = 3 (varint, two's complement for negatives)
        return b"\x18" + varint(int(raw) & 0xFFFFFFFFFFFFFFFF)
    if kind == "float":  # Value.float = 2 (fixed32)
        return b"\x15" + struct.pack("<f", float(raw))
    return field(0x2A, raw.encode())  # Value.string = 5


def entry(arg):
    key, raw = arg.split("=", 1)
    key, _, kind = key.partition(":")
    return field(0x0A, field(0x0A, key.encode()) + field(0x12, value(kind, raw)))  # PreferenceMap.preferences = 1


sys.stdout.buffer.write(b"".join(entry(a) for a in sys.argv[1:]))
