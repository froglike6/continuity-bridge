# Embedded helper lifecycle regression tests

Run `./continuity-bridge/android/run-embedded-lifecycle-tests.sh` from the repository root. The runner compiles the production manager, preferences, and boot receiver into a unique temporary directory and removes it on exit. It does not touch the shared APK build directory or a device.

The small Android and ADB doubles expose queued main-thread callbacks and a controlled worker executor. Tests execute the real manager through specific start, stop, opt-out, and late-completion orderings, including a backend that returns success after cancellation. This covers manager decisions and persisted intent; it does not establish Android Binder, wireless-debugging approval, actual socket cancellation, or device helper behavior. Those require the integration/device checks.

All added sources are test fixtures or regression tests. There are no temporary production log statements or runtime settings to revert.

The initial 11-scenario run against the pre-fix production manager reported `embedded_lifecycle_failures=9`: stale automatic preference writes, lost replacement starts after both success and failure, cancelled helper attachment, missing stop cancellation, pairing opt-out, API 30 automatic privilege requests, and approval polling. The fixed manager passed those scenarios. An additional regression covers opt-out after the worker succeeds while helper attachment is still pending.
