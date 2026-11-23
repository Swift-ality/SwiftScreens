"""Runs console commands on the local test server (run/) over RCON.

    python tools/rcon.py "gamemode creative swiftality" "list"
"""
import pathlib
import socket
import struct
import sys

RUN = pathlib.Path(__file__).resolve().parent.parent / "run"


def props():
    out = {}
    for line in (RUN / "server.properties").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            out[k] = v
    return out


def packet(req_id, kind, body):
    data = struct.pack("<ii", req_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(data)) + data


def read(sock):
    head = sock.recv(4)
    (length,) = struct.unpack("<i", head)
    data = b""
    while len(data) < length:
        data += sock.recv(length - len(data))
    req_id, kind = struct.unpack("<ii", data[:8])
    return req_id, data[8:-2].decode("utf-8", "replace")


def main(cmds):
    p = props()
    with socket.create_connection(("127.0.0.1", int(p.get("rcon.port", "25575"))), timeout=10) as s:
        s.sendall(packet(1, 3, p["rcon.password"]))
        req_id, _ = read(s)
        if req_id == -1:
            raise SystemExit("RCON login refused")
        for i, c in enumerate(cmds, start=2):
            s.sendall(packet(i, 2, c))
            _, body = read(s)
            print(f"> {c}\n{body}")


if __name__ == "__main__":
    main(sys.argv[1:])
