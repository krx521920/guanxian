from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import stat
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from tools.deployment import configure_personal_model_access as setup

ROOT_PATH_CHECK = setup.root_path


class PersonalModelSetupTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.deploy = self.root / 'deployment'
        self.deploy.mkdir()
        (self.deploy / 'secrets').mkdir()
        (self.deploy / 'deploy.env').write_text('FIXTURE=true\n')
        self.key = self.deploy / 'secrets/ai_personal_encryption_key'
        self.overlay = self.deploy / 'personal-model.compose.yml'
        self.helper = self.root / 'guanxian-maintain'
        self.original = b'COMPOSE = ["fixed-compose"]\n' + setup.MARKER + b'\n'
        self.helper.write_bytes(self.original)
        self.patches = [patch.object(setup, name, value) for name, value in {
            'DEPLOY': self.deploy, 'REPO': self.root, 'HELPER': self.helper,
            'KEY': self.key, 'OVERLAY': self.overlay,
            'ORIGINAL_HELPER_SHA256': hashlib.sha256(self.original).hexdigest(),
        }.items()]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        # Unit tests simulate paths/ownership and Docker, never a real root deployment.
        self.paths = patch.object(setup, 'root_path').start()
        self.addCleanup(patch.stopall)
        patch.object(setup, 'require_admin').start()
        self.run = patch.object(setup.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)).start()

    def call(self, execute=False, confirmation=None):
        return setup.configure(execute, confirmation)

    def test_key_validation(self):
        setup.validate_key(base64.b64encode(bytes(range(32))) + b'\n')
        for value in (b'', b'bad-key', base64.b64encode(b'short'), b'!' * 44):
            with self.subTest(value=value), self.assertRaises(ValueError):
                setup.validate_key(value)

    def test_root_path_rejects_symlinks_nonroot_and_unsafe_modes(self):
        target = Mock()
        target.resolve.return_value = target
        target.stat.return_value = SimpleNamespace(st_uid=0, st_mode=stat.S_IFDIR | 0o700)
        ROOT_PATH_CHECK(target, directory=True, private=True)
        for uid, mode in ((1000, stat.S_IFDIR | 0o700), (0, stat.S_IFDIR | 0o755),
                          (0, stat.S_IFDIR | 0o720), (0, stat.S_IFREG | 0o600)):
            target.stat.return_value = SimpleNamespace(st_uid=uid, st_mode=mode)
            with self.subTest(uid=uid, mode=mode), self.assertRaises(ValueError):
                ROOT_PATH_CHECK(target, directory=True, private=True)
        target.resolve.return_value = Mock()
        with self.assertRaisesRegex(ValueError, 'symlink'):
            ROOT_PATH_CHECK(target)

    def test_new_file_never_overwrites(self):
        self.key.write_bytes(b'preserve-existing-master-key')
        with self.assertRaises(FileExistsError):
            setup.new_file(self.key, b'replacement', 0o444)
        self.assertEqual(b'preserve-existing-master-key', self.key.read_bytes())

    def test_helper_upgrade_is_exact_additive_pinned_and_idempotent(self):
        upgraded = setup.upgrade_helper(self.original)
        self.assertEqual(self.original, upgraded.replace(setup.ADDITION, b''))
        self.assertEqual(upgraded, setup.upgrade_helper(upgraded))
        self.assertEqual(1, upgraded.count(setup.ADDITION))
        for unexpected in (self.original + b'#other change', upgraded + setup.ADDITION):
            with self.assertRaises(ValueError):
                setup.upgrade_helper(unexpected)

    def test_default_is_read_only(self):
        before = sorted(self.root.rglob('*'))
        result = self.call()
        self.assertEqual('dry-run', result['mode'])
        self.assertEqual(before, sorted(self.root.rglob('*')))
        self.assertEqual(self.original, self.helper.read_bytes())
        self.run.assert_not_called()

    def test_execute_requires_explicit_egress_confirmation(self):
        with self.assertRaises(ValueError):
            self.call(True)
        self.assertFalse(self.key.exists())
        self.run.assert_not_called()

    def test_execute_creates_protected_configuration_but_never_restarts_or_changes_data(self):
        result = self.call(True, setup.CONFIRMATION)
        setup.validate_key(self.key.read_bytes())
        self.assertEqual(setup.OVERLAY_TEXT, self.overlay.read_text())
        self.assertEqual(self.original, (Path(result['backupDirectory']) / 'guanxian-maintain').read_bytes())
        self.assertEqual(setup.upgrade_helper(self.original), self.helper.read_bytes())
        self.assertNotIn(self.key.read_text().strip(), json.dumps(result))
        self.assertEqual('configured-pending-server-restart', result['status'])
        self.assertFalse(result['databaseChanged'])
        self.assertFalse(result['sudoersChanged'])
        self.assertFalse(result['restartPerformed'])
        command = self.run.call_args.args[0]
        self.assertEqual(['config', '--quiet'], command[-2:])
        self.assertIn(str(self.overlay), command)
        self.assertNotIn('up', command)

    def test_repeat_reuses_key_instead_of_rotating(self):
        self.call(True, setup.CONFIRMATION)
        before = self.key.read_bytes()
        result = self.call(True, setup.CONFIRMATION)
        self.assertEqual(before, self.key.read_bytes())
        self.assertEqual('reuse-existing', result['masterKey'])
        self.assertEqual('already-configured', result['maintenanceHelper'])

    def test_bad_key_or_overlay_is_never_replaced(self):
        self.key.write_bytes(b'bad-key')
        with self.assertRaises(ValueError):
            self.call(True, setup.CONFIRMATION)
        self.assertEqual(b'bad-key', self.key.read_bytes())
        self.key.unlink()
        self.overlay.write_text('unrecognized configuration')
        with self.assertRaises(ValueError):
            self.call(True, setup.CONFIRMATION)
        self.assertFalse(self.key.exists())
        self.assertEqual('unrecognized configuration', self.overlay.read_text())
        self.run.assert_not_called()

    def test_failed_compose_keeps_helper_and_retains_key_for_retry(self):
        self.run.return_value = subprocess.CompletedProcess([], 1, stderr=b'diagnostic-might-contain-secret')
        with self.assertRaisesRegex(ValueError, 'helper unchanged') as failure:
            self.call(True, setup.CONFIRMATION)
        self.assertNotIn('diagnostic-might-contain-secret', str(failure.exception))
        self.assertEqual(self.original, self.helper.read_bytes())
        before = self.key.read_bytes()
        self.run.return_value = subprocess.CompletedProcess([], 0)
        self.call(True, setup.CONFIRMATION)
        self.assertEqual(before, self.key.read_bytes())

    def test_previously_enabled_missing_key_is_not_silently_replaced(self):
        self.helper.write_bytes(setup.upgrade_helper(self.original))
        with self.assertRaisesRegex(ValueError, 'recover it'):
            self.call(True, setup.CONFIRMATION)
        self.assertFalse(self.key.exists())
        self.run.assert_not_called()

    def test_overlay_changes_only_personal_model_access(self):
        import yaml
        data = yaml.safe_load(setup.OVERLAY_TEXT)
        self.assertEqual(['server'], list(data['services']))
        server = data['services']['server']
        self.assertEqual({'GUANXIAN_RAG_EXTERNAL_MODEL_DATA_EGRESS_ENABLED': 'true'}, server['environment'])
        self.assertEqual(['personal-model-egress'], server['networks'])
        self.assertNotIn('ports', server)
        self.assertNotIn('volumes', data)
        self.assertNotIn('GUANXIAN_AI_PROVIDER_ENABLED', setup.OVERLAY_TEXT)
        self.assertNotIn('GUANXIAN_EMBEDDING_ENABLED', setup.OVERLAY_TEXT)


if __name__ == '__main__':
    unittest.main()
