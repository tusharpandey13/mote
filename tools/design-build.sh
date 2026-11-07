#!/bin/sh
# THE step after any design change: gen.py -> DTCG tokens -> PNG gallery. Then commit design/ + docs/design/.
set -eu
cd "$(dirname "$0")/.."
python3 tools/design-gen/gen.py design/mote-v1
node tools/design-export/dtcg.mjs
node tools/design-export/export.mjs
