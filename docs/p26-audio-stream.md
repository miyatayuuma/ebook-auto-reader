# P2.6A: continuous audio — design decision and diagnostic gate

Status: proposed architecture, diagnostic code only; playback replacement not implemented. Preserve Google offline Japanese TTS, reader controls, source mapping and existing first-audio protection.

## Cause and evidence
Current ReaderAudio.play suspends until each clip completes. DeviceAudio prepares a MediaPlayer per WAV and inserts a 280 ms wall-time-equivalent lead after >=750 ms idle. Logs of 188–271 ms between same-page clips are scheduling/head-observation intervals, not acoustic silence. EYES multi-second navigation gates are addressed separately by B-1. Measure raw edge silence, internal punctuation gaps, playback scheduling and actual output separately.

## Decision
Prefer a single MODE_STREAM AudioTrack per output epoch, not one track per utterance. Keep synthesizeToFile and parse completed RIFF chunks initially; no dependence on live TTS callback delivery for correctness. Preserve source speech map and immutable unit IDs. Validate format and frame alignment; normalize to one compatible PCM format per session, including the 16 kHz figure notice. Avoid resampling speech if formats already match. Reject unsupported formats rather than replaying corrupted sound. Keep WAV temporary; release files on cancellation. Bound software queue by existing 20 s / 8 clips / 4 MiB per clip limits, additionally bound hardware lead and account for 2.5x playback. Use public PlaybackParams speed with pitch=1; configure AudioTrack buffer strictly above max speed times getMinBufferSize; report unsupported rate without silently changing rate. See Android API links below.

## Session contract — required change, not a drop-in player swap
Synthesis readiness != queued != submitted to sink != actually played. An output worker owns track and writes ahead of previous clip completion (bounded, partial writes handled; no blocking on main). A playback ledger tracks source unit and normalized PCM frame spans; completed/confirmed positions advance ONLY when playback-head evidence crosses the corresponding end. Use timestamp/head wrap handling and account for route changes, resets and pause/flush. Start first ready audio without waiting for full software queue; retain proven initial lead only for output startup or actual route restart. No lead at ordinary unit boundaries. Queue/clip close ownership explicit; discard on generation change, cancel/failure; no stale events after restart. Stop promptly (pause, flush, release); resume from confirmed source boundary, never from bytes merely written. No automatic replay on ambiguous output failure.

EARS may enqueue verified subsequent units while earlier units play. EYES may enqueue multiple units of the currently verified page only; gate next-page PCM before submission, not after it has reached the hardware queue. At a boundary drain confirmed old-page audio, move once, verify target, then allow next-page output. B-2 may split a combined utterance at a validated source-frame boundary; if absent, synthesize page-local slices with no invented punctuation. Keep logical source continuity and EARS concatenation. A range spanning the boundary is not a precise page marker. Never estimate source offset linearly by audio duration.

## Naturalness
First compare single-sentence WAV vs multiple natural sentences per utterance with exactly the same original text and reading map. Larger synthesis batches may improve prosody and remove excess edge pauses but must not delay first ready speech or violate memory/character limits. Preserve internal punctuation; remove no audio initially. If edge silence is still audibly excessive, detect conservative boundary-only silence with guard margin; keep low-energy phonemes and avoid amplitude discontinuities. Source-time pause duration should scale with speed. No arbitrary fixed wall-time gap between units; no silent truncation.

## Device diagnostic
The existing debug voice audition now records format, callback byte count and range summary (no text/audio in logs).
```bash
adb shell am start -n io.github.ebookautoreader.debug/io.github.ebookautoreader.reader.DiagnosticActivity --es test voice
adb logcat -d -s EbookReader:I | grep 'tts probe' | tail -n 5
```
Use existing synthetic sample WAV export for onset, tail and punctuation analysis; never export purchased-book samples. Range callbacks are engine-dependent and file-synthesis support is not guaranteed by the public API; absence or invalid data must not block continuous playback. If range data exists, verify actual coverage and frame bounds before any use. API documentation describes onRangeStart as playback-related, and AOSP versions differ in documented behavior; empirical result decides.

## Next implementation tasks
A1: WAV parser/PCM queue/AudioTrack sink and source-frame ledger; pure tests including malformed chunks, partial writes, mixed formats, cancel/flush, frame wrap and old-generation events. A2: integrate ReaderSession consumer so multiple eligible units are enqueued ahead, retain state/position/reporting and EYES authorization boundary. Test underflow, first audio, user stop, resume, live speed, figure notice, old audio never leaking. A3: compare synthetic waveforms and listening at 100/200/250%; adjust batch/edge treatment only when evidence warrants. Initial engineering targets: no underruns when sufficient eligible PCM exists; no unplanned app-inserted gaps between eligible PCM; pause/stop no stale next unit; all source characters preserved in order. Physical output and Bluetooth route timing require Pixel acceptance; no performance claims before measurement.

References: https://developer.android.com/reference/android/media/AudioTrack ; https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener ; https://developer.android.com/reference/android/speech/tts/SynthesisCallback ; https://developer.android.com/reference/android/speech/tts/TextToSpeech
