# P2.6D-1: header OCR diagnostic

Compare narrow number OCR and wide header OCR on the same captured image. Wide crop is an experimental 2–98% X, 8.2–11% Y region. Existing navigation is unchanged. Header title is a candidate only; no book ID is assigned. No title or images are logged or saved. Use synthetic examples in shared records.

On a displayed DMM page, while idle:

```bash
adb shell am broadcast -a io.github.ebookautoreader.DEBUG_READER -p io.github.ebookautoreader.debug --es command header --ei expected-number 120
adb logcat -d -s EbookReader:I | grep 'header result=' | tail -n 1
```

Optional `--es expected-title` compares a locally supplied title (do not upload command history). Check 2–3 books, multiple pages, including numeric title prefixes and truncation. Compare narrowMs/wideMs, numberAgreement, expectedNumberMatch, titlePresent/titleMatch. Measure warmed runs separately. Until validated, never use title/number as identity proof. No runtime book index, title signature reuse or automatic navigation change yet.
