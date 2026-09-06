"""Contract checks for the pinned Keycloak 26.7.1 disposable realm."""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]

class AccountUserProfileFixtureTest(unittest.TestCase):
    def setUp(self):
        self.profile = json.loads((ROOT / "tests/identity/account-user-profile.fixture.json").read_text(encoding="utf-8"))

    def test_unmanaged_attributes_use_the_disabled_default(self):
        # Keycloak 26.7.1 UPConfig enum has ENABLED / ADMIN_VIEW / ADMIN_EDIT;
        # omission/null is disabled. The string DISABLED fails JSON deserialization.
        self.assertIsNone(self.profile.get("unmanagedAttributePolicy"))

    def test_provisioning_marker_is_admin_only_and_single_valued(self):
        attributes = {item["name"]: item for item in self.profile["attributes"]}
        self.assertTrue({"username", "email", "firstName", "lastName"}.issubset(attributes))
        marker = attributes["guanxianProvisioningId"]
        self.assertFalse(marker["multivalued"])
        self.assertEqual({"view": ["admin"], "edit": ["admin"]}, marker["permissions"])
        self.assertEqual({"min": 36, "max": 36}, marker["validations"]["length"])

if __name__ == "__main__":
    unittest.main()
