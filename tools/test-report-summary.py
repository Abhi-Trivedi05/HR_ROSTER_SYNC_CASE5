"""Print actual Surefire/Failsafe totals from a selected Maven build directory."""
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'target')
result = {}
for kind in ('surefire-reports', 'failsafe-reports'):
    rows = [ET.parse(path).getroot() for path in (root / kind).glob('TEST-*.xml')]
    result[kind] = {key: sum(int(row.get(key, 0)) for row in rows)
                    for key in ('tests', 'failures', 'errors', 'skipped')}
print(json.dumps(result, indent=2))
