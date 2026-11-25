import socket

import pytest

from app.serve import dual_stack_socket


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@pytest.mark.parametrize("client", ["127.0.0.1", "::1"])
def test_socket_accepts_ipv4_and_ipv6_clients(client):
    if client == "::1" and not socket.has_ipv6:
        pytest.skip("no IPv6")
    port = _free_port()
    server = dual_stack_socket(port)
    try:
        server.listen()
        if server.family == socket.AF_INET and client == "::1":
            pytest.skip("IPv6 disabled on this host")
        family = socket.AF_INET6 if ":" in client else socket.AF_INET
        with socket.socket(family, socket.SOCK_STREAM) as c:
            c.settimeout(2)
            c.connect((client, port))
            conn, _ = server.accept()
            conn.close()
    finally:
        server.close()
