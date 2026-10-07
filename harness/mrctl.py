#!/usr/bin/env python3
"""Entry point: python3 harness/mrctl.py <command> ... (see mrctl/cli.py)."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mrctl.cli import main  # noqa: E402

sys.exit(main())
