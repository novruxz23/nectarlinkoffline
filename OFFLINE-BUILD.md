# Offline build changes

This copy was modified so nothing leaves the phone/PC pair (LAN or USB only).

- core/nectarlink-core/src/node.rs: relays disabled (RelayMode::Disabled), away mode ("Connect over mobile data") forced off, public Pkarr/DNS address publishing and lookup removed, relay probing disabled.
- Android: AppUpdater can no longer open any connection (URLs emptied, `connect` always fails, `enabled` = false); update card and "Check for updates" removed; REQUEST_INSTALL_PACKAGES and UPDATE_PACKAGES_WITHOUT_USER_ACTION permissions removed; GitHub star notification and Support button removed; allowBackup=false; voice typing limited to on-device recognition.
- Desktop: updater disabled (can_update=false, check_soon/run_service no-ops, feed::fetch always errors); auto_update/install_updates default to false; Windows installer no longer creates the "Nectarlink Update" SYSTEM task (it deletes an old one).
- Side effects: "install phone app from PC" (downloads the APK from GitHub) no longer works; install the APK yourself. Update unit tests in desktop/app/src/feed.rs and updater.rs may fail. Docs (docs/guide/privacy-and-security.md) still describe the old behaviour.
- NOT built or tested: no compiler/network was available here. Build and test before use.
