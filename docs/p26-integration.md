# P2.6 first-wave integration — acceptance candidate

Base f3712a185c647cbe5e5e07357845ef27d5378665. Integrated PRs #11 (header diagnostic), #12 (brightness), #13 (EYES cold-page policy), #14 (audio design and synthetic TTS diagnostic). Individual P2 Reader CI passed; integration CI and Pixel acceptance required. Audio output still uses MediaPlayer. No book index or title identity claim.

## Pixel 9 acceptance (one APK)
Install the successful P2 Reader artifact for the integrated commit with existing signing key and models. Record exact commit and run ID, not legacy versionName.

1. Settings: grant system settings write permission if desired. EARS start -> minimum brightness, pause -> restore, resume -> minimum, end -> restore. Check automatic brightness restoration; EYES must not dim. Verify user slider changes and app departure/lock.
2. EYES: read 3–5 pages including an unfinished sentence; no speculative forward/back turns. Cold page boundary waits are expected. Pause/resume immediately after one forward turn; no stale speech or backward restoration. EARS still prefetches.
3. While idle, use debug header command on 2–3 books and adjacent pages; see p26-header-identity.md. Compare narrow/wide number, title match, timing. No title or raw images in shared logs.
4. Use existing synthetic voice diagnostic, then inspect tts probe counts. See p26-audio-stream.md. No purchased book WAV export.
5. Existing cache should still hit on repeated exact frames. No full re-OCR or cache reset.

Keep public logs sanitized. No merge to main until product acceptance. For force-stop or permission revocation, brightness recovery is best effort on service reconnect; manual slider remains available.
