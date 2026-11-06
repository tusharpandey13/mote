# Fonts

| File | Font | Source | License |
|---|---|---|---|
| Unbounded-Black.woff2 | Unbounded, Black (wght 900) | https://raw.githubusercontent.com/google/fonts/main/ofl/unbounded/Unbounded%5Bwght%5D.ttf (google/fonts repo, `ofl/unbounded`) | SIL Open Font License 1.1, see OFL.txt (from the same folder) |

The variable font was pinned to wght=900 with `fonttools varLib.instancer`, subset to A-Z, 0-9 and space with `pyftsubset`, and converted to woff2. The OFL allows modified versions as long as they are not sold on their own.
It is used only for the all-caps "MOTE" wordmark. Unbounded is wide and heavy with squared-off curves, so a debossed cut of it reads like a stamped hardware badge.
Revision 3 replaced Michroma (one weight only, too light). Syncopate was considered and rejected: google/fonts ships it under Apache 2.0, not the OFL.
