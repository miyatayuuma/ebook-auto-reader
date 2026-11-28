# P2.7 TalkBack auto-turn — minimal product specification

The Android app is only a TalkBack page-turn companion. It does not implement a reader engine. Separate package io.github.ebookautoreader.lite, project p27/lite. Existing OCR product and acquired private data are untouched.

## Required behavior
- Enable the service once. In DMM PublusReaderActivity, TalkBack ON starts; OFF ends. No per-session app launch.
- Use public AudioPlaybackCallback for accessibility usage and unknown/speech content, with an immediate playback snapshot before each swipe. This is an audio-state proxy, not private TalkBack utterance data.
- On start and after each completed gesture, reset the timer using current playback state. While speech is active, never turn. If speech ended, wait 800 ms (500–4000 configurable). If no speech, wait 1500 ms (1000–8000 configurable). Every speech restart cancels the pending timeout. No content-dependent rules or limit on consecutive silent pages.
- One simultaneous two-finger horizontal swipe, 120 ms, equal displacement from 22% to 78% of reader width (or reversed), y=43% and 57%. Default rightward; explicit direction setting. Do not overlap injected gestures; wait for completion and then start the next timer. The callback confirms input delivery, not DMM page advancement. A cancelled gesture restarts the wait, never immediate retry; three consecutive cancellations, rejected dispatch or missing completion stop safely.
- Manual page turns do not stop the session or require position synchronization. Continue monitoring speech. No page index or image verification.
- Input is restricted to the foreground DMM reader activity, not shelf/settings/other apps. Hold for notification shade or detected reader menu; reset timer when returning to same reader. Lock/off, lost reader, emergency stop terminate; rearm with TalkBack OFF/ON. Use only window and control metadata, never node text.
- Keep display awake with a bounded/renewed SCREEN_DIM_WAKE_LOCK during the session, release on stop. Notification and optional QS tile provide emergency stop. No global brightness changes.
- No screenshot capability, pixel acquisition, image signatures, classification, OCR, independent TTS, audio recording, content retrieval/storage, network, prefetch or book-position synchronization.

## Acceptance
Install CI artifact from the exact successful run. Disable previous Reader and Probe services, enable Ebook Auto Turn and notifications. Open reader with TalkBack OFF, then ON. Set correct direction once. Check logs with adb logcat -v threadtime -s EbookAutoTurn:I. Validate 120ms swipe delivery and visible forward motion, ordinary body and short/long utterances, several consecutive silent pages including the first page, manual forward/back, TalkBack OFF, menus/shade/lock, and disconnected face-down reading. Prioritize real swipe success and stable speech over additional features.

Assume normal DMM/TalkBack operation. No direct detection of a silent failed swipe, identical pages, book end or speech-start delay; swipes continue at the configured interval while reader is in scope and silent until TalkBack OFF or emergency stop. All speech including operation announcements is monitored; no unsupported semantic classification. Final acceptance requires real device results; CI alone does not establish gesture accuracy.
