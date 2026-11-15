#!/usr/bin/env python3
"""Frozen Mirage keeps mutable data in the user's profile."""

import importlib.util
import json
import os
import sys
import tempfile
from pathlib import Path


def test_frozen_data_outside_install():
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        install = root / 'Program Files' / 'Mirage'
        local = root / 'user' / 'AppData' / 'Local'
        (install / 'servers').mkdir(parents=True)
        (install / 'configs').mkdir()
        (install / '.state').mkdir()
        (install / 'servers' / 'starter.ovpn').write_text('bundled')
        (install / 'servers' / 'new.ovpn').write_text('new')
        (install / 'configs' / 'manual.ovpn').write_text('manual')
        (install / '.state' / 'accounts.json').write_text('sealed account')
        (install / '.state' / 'settings.json').write_text(json.dumps({
            'folder': str(install / 'servers'),
            'pinFolder': str(install / 'configs'),
            'source': 'folder:' + str(install / 'servers'),
            'external': str(root / 'elsewhere')}))
        (install / '.state' / 'dropped.json').write_text(json.dumps({
            'servers/old.ovpn': {'from': str(install / 'servers')}}))
        (install / '.state' / 'proxy.state').write_text('stale process')
        (install / '.state' / 'system-proxy-before.json').write_text('stale proxy')
        (install / '.ovpn-auth').write_text('service credentials')
        (install / 'gost.exe').write_bytes(b'client')
        (install / 'Resolve-OvpnRemote.ps1').write_text('script')

        data = local / 'Mirage'
        (data / 'servers').mkdir(parents=True)
        (data / 'servers' / 'starter.ovpn').write_text('user version')

        old_exe = sys.executable
        old_frozen = getattr(sys, 'frozen', None)
        old_local = os.environ.get('LOCALAPPDATA')
        try:
            sys.executable = str(install / 'Mirage.exe')
            sys.frozen = True
            os.environ['LOCALAPPDATA'] = str(local)
            spec = importlib.util.spec_from_file_location(
                'mirage_frozen_paths', Path(__file__).with_name('paths.py'))
            paths = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(paths)
        finally:
            sys.executable = old_exe
            if old_frozen is None:
                del sys.frozen
            else:
                sys.frozen = old_frozen
            if old_local is None:
                os.environ.pop('LOCALAPPDATA', None)
            else:
                os.environ['LOCALAPPDATA'] = old_local

        assert Path(paths.DATA_DIR) == data
        assert Path(paths.AUTH_FILE) == data / '.ovpn-auth'
        assert Path(paths.SCRIPTS_DIR) == install
        assert Path(paths.gost_exe()) == install / 'gost.exe'
        class ProxyPaths:
            pass
        proxy_paths = ProxyPaths()
        paths.point_proxy_module_at_data(proxy_paths)
        assert Path(proxy_paths.TUNNEL_SETTINGS) == data / '.state' / 'tunnel.json'
        assert Path(proxy_paths.TUNNEL_STATE) == data / '.state' / 'tunnel'
        paths.prepare_data()
        assert (data / 'servers' / 'starter.ovpn').read_text() == 'user version'
        assert (data / 'servers' / 'new.ovpn').read_text() == 'new'
        assert (data / 'configs' / 'manual.ovpn').read_text() == 'manual'
        assert (data / '.state' / 'accounts.json').read_text() == 'sealed account'
        settings = json.loads((data / '.state' / 'settings.json').read_text())
        assert settings['folder'] == str(data / 'servers')
        assert settings['pinFolder'] == str(data / 'configs')
        assert settings['source'] == 'folder:' + str(data / 'servers')
        assert settings['external'] == str(root / 'elsewhere')
        dropped = json.loads((data / '.state' / 'dropped.json').read_text())
        assert dropped['servers/old.ovpn']['from'] == str(data / 'servers')
        assert not (data / '.state' / 'proxy.state').exists()
        assert not (data / '.state' / 'system-proxy-before.json').exists()
        assert (data / '.ovpn-auth').read_text() == 'service credentials'
        (data / '.state' / 'accounts.json').write_text('new user version')
        (data / 'servers' / 'later.ovpn').write_text('user later')
        paths.prepare_data()
        assert (data / 'servers' / 'later.ovpn').read_text() == 'user later'
        assert (data / '.state' / 'accounts.json').read_text() == 'new user version'


def _frozen_paths(install, local):
    """paths.py imported as a built Mirage.exe in `install` would see it."""
    old_exe = sys.executable
    old_frozen = getattr(sys, 'frozen', None)
    old_local = os.environ.get('LOCALAPPDATA')
    try:
        sys.executable = str(install / 'Mirage.exe')
        sys.frozen = True
        os.environ['LOCALAPPDATA'] = str(local)
        spec = importlib.util.spec_from_file_location(
            'mirage_frozen_paths', Path(__file__).with_name('paths.py'))
        paths = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(paths)
        return paths
    finally:
        sys.executable = old_exe
        if old_frozen is None:
            del sys.frozen
        else:
            sys.frozen = old_frozen
        if old_local is None:
            os.environ.pop('LOCALAPPDATA', None)
        else:
            os.environ['LOCALAPPDATA'] = old_local


def test_legacy_data_folder_moves():
    """%LOCALAPPDATA%\\Relay becomes %LOCALAPPDATA%\\Mirage on the first
    start, folders remembered inside it follow, and nothing else is touched."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        install = root / 'Program Files' / 'Mirage'
        install.mkdir(parents=True)
        local = root / 'user' / 'AppData' / 'Local'
        old = local / 'Relay'
        (old / '.state').mkdir(parents=True)
        (old / 'servers').mkdir()
        (old / 'servers' / 'mine.ovpn').write_text('mine')
        (old / '.state' / 'accounts.json').write_text('sealed account')
        (old / '.state' / 'settings.json').write_text(json.dumps({
            'folder': str(old / 'servers'),
            'source': 'folder:' + str(old / 'pinned'),
            'external': str(root / 'elsewhere'),
            'port': 8080}))
        (old / '.state' / 'dropped.json').write_text(json.dumps({
            'servers/x.ovpn': {'from': str(old / 'servers')}}))

        paths = _frozen_paths(install, local)
        new = local / 'Mirage'
        assert Path(paths.DATA_DIR) == new
        assert not old.exists()
        assert (new / 'servers' / 'mine.ovpn').read_text() == 'mine'
        assert (new / '.state' / 'accounts.json').read_text() == 'sealed account'
        settings = json.loads((new / '.state' / 'settings.json').read_text())
        assert settings['folder'] == str(new / 'servers')
        assert settings['source'] == 'folder:' + str(new / 'pinned')
        assert settings['external'] == str(root / 'elsewhere')
        assert settings['port'] == 8080
        dropped = json.loads((new / '.state' / 'dropped.json').read_text())
        assert dropped['servers/x.ovpn']['from'] == str(new / 'servers')

        # Both there: Mirage is used and Relay is left exactly as it was.
        (old / '.state').mkdir(parents=True)
        (old / '.state' / 'accounts.json').write_text('older copy')
        paths = _frozen_paths(install, local)
        assert Path(paths.DATA_DIR) == new
        assert (old / '.state' / 'accounts.json').read_text() == 'older copy'
        assert (new / '.state' / 'accounts.json').read_text() == 'sealed account'

    with tempfile.TemporaryDirectory() as tmp:
        # Neither there: a fresh install, and the import creates nothing.
        local = Path(tmp) / 'Local'
        local.mkdir()
        paths = _frozen_paths(Path(tmp) / 'app', local)
        assert Path(paths.DATA_DIR) == local / 'Mirage'
        assert list(local.iterdir()) == []

    with tempfile.TemporaryDirectory() as tmp:
        # The move refused (the old copy still open): this run keeps using
        # Relay where it is, rather than starting from an empty folder.
        local = Path(tmp)
        (local / 'Relay').mkdir()
        spec = importlib.util.spec_from_file_location(
            'mirage_paths_refused', Path(__file__).with_name('paths.py'))
        paths = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(paths)
        real = os.rename

        def refuse(a, b):
            raise PermissionError(32, 'in use')
        os.rename = refuse
        try:
            assert Path(paths.adopt_legacy_data(str(local))) == local / 'Relay'
        finally:
            os.rename = real
        assert Path(paths.adopt_legacy_data(str(local))) == local / 'Mirage'
        assert (local / 'Mirage').is_dir() and not (local / 'Relay').exists()


if __name__ == '__main__':
    test_frozen_data_outside_install()
    print('ok test_frozen_data_outside_install')
    test_legacy_data_folder_moves()
    print('ok test_legacy_data_folder_moves')
