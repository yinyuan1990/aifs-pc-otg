WinDivert (LGPL v3) — required to build winshaper
==================================================

Bundled in this tree: official WinDivert-2.2.2-A (x64/x86, include, LICENSE).
Source: https://github.com/basil00/WinDivert/releases/tag/v2.2.2

1. Download WinDivert from: https://reqrypt.org/windivert.html
   (or https://github.com/basil00/WinDivert/releases)

2. Extract so that this layout exists (example):

   third_party/WinDivert/
     include/windivert.h
     x64/WinDivert.lib
     x64/WinDivert.dll
     x64/WinDivert64.sys

3. Run CMake with -DWINDIVERT_ROOT=... pointing at that folder, or place files
   under winshaper/third_party/WinDivert as above.

4. Run winshaper.exe as Administrator. WinDivert64.sys must be loadable
   (signed driver from the official package).

5. Copy WinDivert.dll next to the executable (winshaper.exe or zjc_worker.exe when embedded).

6. Phoenix / zjc_worker: placing this folder under winshaper/third_party/WinDivert enables
   CMake option ZJC_EMBED_WINSHAPER (auto ON when windivert.h exists) so zjc_worker.exe
   includes the shaper — no separate winshaper.exe in the release zip.

Compliance: winshaper links dynamically to WinDivert.dll; distribute WinDivert
binaries and license (LGPL) per WinDivert project requirements.
