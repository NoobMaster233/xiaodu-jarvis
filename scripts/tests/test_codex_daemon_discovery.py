"""Regression coverage for daemon management and RPC sockets coexisting."""
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import socket

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/'system/codex-bridge'))
import bridge


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.directory = Path(self.folder.name)

    def endpoint(self, name):
        path = self.directory/name
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        listener.bind(str(path))
        listener.listen()
        self.addCleanup(listener.close)
        return path

    def test_management_and_app_server_can_coexist(self):
        supervisor = self.endpoint('management')
        server = self.endpoint('rpc')
        with patch.object(bridge, 'is_app_server_socket', side_effect=lambda p: p == server):
            self.assertEqual(bridge.discover_daemon_socket(self.directory), server)

    def test_non_socket_files_are_ignored(self):
        server = self.endpoint('rpc')
        (self.directory/'rpc.lock').touch()
        with patch.object(bridge, 'is_app_server_socket', return_value=True):
            self.assertEqual(bridge.discover_daemon_socket(self.directory), server)

    def test_no_rpc_server_is_not_a_management_connection(self):
        self.endpoint('management')
        with patch.object(bridge, 'is_app_server_socket', return_value=False):
            with self.assertRaisesRegex(bridge.DaemonDiscoveryError, 'app_server_not_found'):
                bridge.discover_daemon_socket(self.directory)

    def test_multiple_rpc_servers_are_not_guessed(self):
        self.endpoint('rpc-a')
        self.endpoint('rpc-b')
        with patch.object(bridge, 'is_app_server_socket', return_value=True):
            with self.assertRaisesRegex(bridge.DaemonDiscoveryError, 'multiple_app_servers'):
                bridge.discover_daemon_socket(self.directory)

    def test_other_process_socket_is_not_a_server(self):
        server = self.endpoint('stale')
        # This process is Python, not Codex; an actual peer cannot be misidentified.
        self.assertFalse(bridge.is_app_server_socket(server))

    def test_stale_socket_is_not_a_server(self):
        path = self.directory/'stale'
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
            listener.bind(str(path))
        self.assertFalse(bridge.is_app_server_socket(path))


if __name__ == '__main__':
    unittest.main()
