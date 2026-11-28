# P2.6B-1: forward-only cold-page EYES policy

No navigation prefetch while a page is being decoded or its speech is pending. Advance only after all current-page audio is completed. EYES flushes page-tail fragments as speech units without editing original text; EARS retains cross-page concatenation and existing prefetch. Unknown positions still fail closed. Existing cache and prepared start remain available, but saved successor lookup is a later task.

Tradeoff: a cold page can incur page-turn/OCR/synthesis delay at the boundary, and a sentence crossing that boundary can sound fragmented. Preserve exact source spans and do not add punctuation. P2.6B-2 should use validated cached successors and actual audio-range evidence where available to improve this. No claim of character-level synchronization yet.

Tests: no page turn during OCR or playback; forward-only order; cross-page source characters preserved; EARS regressions remain. Pixel check with several cold pages and one cross-page sentence; page stays visible during playback, only forward move at boundary, pause/end safe. CI and real-device acceptance required.

Integration: pause/end in EYES verifies and retains the visible page, including a verified next page whose OCR/synthesis has not finished. Unknown interrupted turns still fail closed.
