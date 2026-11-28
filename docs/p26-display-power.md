# P2.6E: ears-mode display power

Uses public Settings.System API (WRITE_SETTINGS granted explicitly by the user). Minimum is 1 per Android API. Default enabled, permission absent means reading continues at normal brightness. EYES mode is unchanged. No overlay or screen lock bypass.

On transition to READING in EARS, persist original mode/level before setting manual minimum. Restore when leaving READING, on service destruction and on service reconnect after process death. If the current settings differ from the app's intended minimum or the partial-change state, do not overwrite them. Revoked permission or killed process before service reconnect can delay recovery; user can always change the system slider manually. This is a best-effort global setting, not a guaranteed device-level luminance override. DMM may have its own window brightness.

Pixel acceptance: grant write-settings permission; manual and automatic brightness; EARS start/pause/resume/end; EYES unchanged; manual slider override; lock or switch app; no OCR regression. Confirm actual luminance and restoration. No additional install for this PR alone is necessary.
