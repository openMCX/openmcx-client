#
# OpenMCX Licence Information : AGPL-3.0-or-later
#

"""Basic executable contract of mcx-client; the openmcx-server lab scenarios supply behavioural coverage.

tests/test_binary.py [path/to/mcx-client]     (default: build/mcx-client)
"""

import pathlib
import subprocess
import sys
import unittest

BINARY = pathlib.Path(__file__).resolve().parents[1] / 'build' / 'mcx-client'


class Binary(unittest.TestCase):
    def test_requires_configuration(self):
        self.assertTrue(BINARY.is_file(), f'{BINARY} has not been built')
        result = subprocess.run([str(BINARY)], capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('--config', result.stderr)


if __name__ == '__main__':
    if len(sys.argv) > 1:
        BINARY = pathlib.Path(sys.argv.pop(1)).resolve()
    unittest.main()
