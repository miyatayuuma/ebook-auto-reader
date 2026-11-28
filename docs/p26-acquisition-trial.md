# P2.6F-1 — bounded local acquisition trial

Debug only. Explicit ADB command, authorized DMM visible page and regular screenshot/gesture APIs. Only for lawful personal use where the service permits it; no secure-window bypass, extraction from DRM storage, title/text/image/audio export or permanent product archive. The existing debug cache holds OCR results; the journal stores only numeric page order and exact image/cache hashes. No duplicate text store. Clear OCR cache from settings to remove data and journal. This does not implement reading from an indexed successor yet.

The acquisition entry gate requires the foreground DMM window and no detected reader menu. The optional bookPageView node is diagnostic only (it is a prewarm hint, not a prerequisite). Before any OCR or turn, the normal stable capture and exact expected page-number checks still apply. OS screenshot restrictions remain enforced. Rejection logs distinguish invalid range, wrong owner and open menu.

## Run

Open the same book at numbered page 1, close all menus. Keep layout, orientation, page direction and theme fixed. Ensure device remains awake and sufficiently powered using normal device settings. Do not interact with the book during acquisition.

First validate 1–5 with a new trial:
```bash
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command acquire --ei first 1 --ei last 5 --ez reset true
adb logcat -s EbookReader:I | grep --line-buffered 'acquire '
```
Then manually return to page 1 and start the requested 1–596 trial, explicitly replacing only the journal (OCR cache retained):
```bash
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command acquire --ei first 1 --ei last 596 --ez reset true
```
Notification End or the following stops it:
```bash
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command acquire-stop
```
After interruption, query the durable checkpoint (works even without a reader window):
```bash
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command acquire-status
adb logcat -d -s EbookReader:I | grep 'acquire status' | tail -n 1
```
Manually return to the checkpoint's last saved page. Resume with identical first/last and omit reset (do NOT use reset). If exact pixels/model/crop or cache contents differ, stop rather than guess. If interrupted before the first checkpoint, open first page again. No automatic 595-page reverse traversal; return to desired reading start using DMM's own navigation.

Strict consecutive *visible* page numbers required. Unnumbered pages, skipped numbers, duplicates, changed windows, OCR/save failures stop; no silent skipping. Actual physical book coverage and OCR accuracy still require human sampling; a sequence 1..596 is not proof of no unnumbered front matter or of exact text. If first/last printed numbers are different from assumed range, choose the correct range. Estimate duration only after short run. The journal is a single trial, not a trusted book identity index; D-2 will handle that separately.
