# MP4 packaging performance

## Changes

- Present decrypted HLS TS cache files as one seekable `MediaDataSource`. A prefix-offset index supports seeks across part boundaries, and a 256 KiB read-ahead buffer limits small file reads. The normal path no longer writes a complete intermediate TS file.
- Package encoded samples into MP4 with their original presentation timestamps and existing ADTS AAC normalization. Cache each packet's timestamp and flags before writing its AAC frames, avoiding repeated extractor-service calls for the same packet.
- For document/shared-storage destinations, use an app-cache MP4 and a sequential 256 KiB final copy when free cache space can cover the scratch file, final copy, and a 64 MiB reserve. This reduces the overhead of many small muxer writes to shared storage. With less space, keep direct output.
- Buffer subtitle output in 256 KiB blocks. Subtitle embedding still performs a separate MP4 pass; this change does not combine subtitle and video authoring into one pass.
- Keep the merged-file fallback for unsupported inputs. Keep cached parts until packaging succeeds, propagate cancellation instead of starting a fallback, and remove scratch files on completion, error, or cancellation. Abandoned scratch files expire after seven days.
- Distinguish saving the MP4 from packaging it in the progress message. Log packaging byte count and elapsed time without media URLs or titles.

## Device measurements

Measured on the same development phone and its configured download document provider on October 30, 2026. Both APKs used version 1.2.0. The baseline APK includes the preceding JavRate authorization fix.

The synthetic 120-second fixture contains H.264 with B-frames and AAC, totaling 95,976,632 TS bytes. A loopback HLS server supplies identical unaligned parts to the actual download service. Polling records every 50 ms measures the interval from progress 99 through completion, including packaging and final storage. These service measurements exclude subtitle embedding.

| Pipeline | Post-download processing | Total including loopback download |
| --- | ---: | ---: |
| Previous APK | 10.485 s | 11.633 s |
| Optimized APK | 3.088 s | 4.082 s |

Post-download processing fell by approximately 71% in this comparison. This is a device-specific synthetic measurement, not a promise for every file, storage provider, or phone.

Separate subtitle measurements on the same 92,968,880-byte MP4 took 550 / 365 / 339 ms with the previous APK and 509 / 294 / 291 ms with buffering. Cache state and warm-up affect these subsecond measurements.

A second, approximately 30-minute synthetic fixture (28,055,052 TS bytes, repeated H.264 B-frame/AAC test footage) exercises many more samples at a lower bitrate. This time the optimized APK ran before the baseline APK. Post-download processing took **24.273 s versus 66.669 s**, approximately **64% less time**. Total times including the loopback download were 24.766 s and 67.165 s. The output contained 17,925 video packets and 77,969 audio packets; all packet hashes and timestamps matched the native-file reference, and full decoding passed.

The final installed APK repeated the long-fixture service test in 23.964 s of processing (24.502 s total). Long-fixture subtitle sample/timing checks and full decoding also passed. The test runner and its synthetic phone media were removed afterward. App version remains 1.2.0 (120).

## Correctness checks

- Compare complete encoded audio/video sample hashes, sample durations, and B-frame composition offsets between native-file input and the virtual part source.
- Verify random seeks, unaligned part boundaries, end-of-file behavior, missing parts, and cancellation propagation with the source retained.
- Run the actual download service against the configured storage provider; verify completion at 100%, an MP4 result, and no intermediate TS record on the fast path. Remove only the test's own temporary download record and output.
- For the 120-second service output, compare every packet's SHA-256, PTS, DTS, duration, and size with the baseline MP4. Full FFmpeg decoding of both the service output and the subtitled output completed without errors.
- Run existing subtitle and ADTS regression tests, including overlapping Chinese captions, subtitle timing, legacy edit-list repair, and audio normalization.
- Nine targeted JVM tests and six Android checks passed. The two JavRate session regression tests also passed, preserving the preceding token-renewal fix.

The user's previous 2 GB website download was verified before this optimization. It has not been downloaded again as part of these synthetic benchmarks.

## Reproduction

Create a synthetic TS with FFmpeg, then place it at `files/packaging-benchmark.ts` in the debuggable app sandbox. The opt-in Android tests skip the fixture-dependent checks when it is absent.

Run `PackagingBenchmarkTest` for seek/cancellation/sample comparisons and `PackagingServiceTest` for end-to-end timings. Run `SubtitlePackagingBenchmarkTest` after the remux test has produced `cache/packaging-baseline.mp4`. The tests save plain-text timings in the app cache. To compare an earlier APK, run only the service and subtitle benchmarks that use existing public app interfaces.

Use synthetic fixtures only. Test-created media are disposable and should be removed after inspection; the benchmark does not require website sessions or credentials.
