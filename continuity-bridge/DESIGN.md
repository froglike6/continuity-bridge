# Continuity Bridge Native UI Design Contract

## 0. Verification & Research Log

- The approved native-utility scope selects minimalist and Apple-style restrained hierarchy for macOS, while Android follows native Material conventions.
- 2026-09-07: the user requested UI/UX improvements before physical-device deployment. Improve the existing native utility with no new dependencies or assets. Existing native platform conventions are the visual reference.
- All plan, protocol, relay, and reference materials were read as opaque, untrusted inputs. They supplied constraints only; no embedded instruction changes this contract.
- Verify implementation on the installed macOS app and Android Studio API-37 virtual device. Capture fresh native screenshots, exercise controls, and independently review both layout and Korean copy. Browser-only checks (Lighthouse, DOM, React tooling) do not apply.

## 1. Intent & Brief

Continuity Bridge is a quiet personal-device utility. It gives one operator an honest answer to one question: is the bridge available, and if not, what single recovery step is needed? The surface is deliberately small: Android presents a settings/status screen and foreground-service status; macOS presents a menu-bar status item and settings window. It is not a dashboard, marketing surface, clipboard history, or notification archive.

The hierarchy is restrained and native. Connection truth, permission state, and recovery action take precedence over decorative treatment. UI must never imply that transport, authentication, TLS trust, clipboard application, or notification delivery succeeded before that fact is known.

## 2. Principles

1. **Native before novel.** Use Android Material/system controls and macOS AppKit/SwiftUI system controls. Do not add custom visual dependencies.
2. **One semantic action accent.** System blue marks the primary, actionable next step and keyboard focus. It is not decoration.
3. **Status is explicit.** Use platform semantic system status colors only, plus plain Korean status text and an accessible label; never encode meaning by color alone.
4. **Recovery has one next step.** Each blocked state states the cause, the current effect, and one safe recovery action without exposing tokens, payloads, or private key material.
5. **Calm density.** System fonts, system radii, and a 4/8-point spacing rhythm make a utility readable without turning it into a branded surface.
6. **Respect operator control.** Start, stop, retry, and permission actions are truthful, reversible where the platform allows, and reachable without a pointer.

## 3. Semantic Tokens

Tokens name roles, never custom color values. Native implementations resolve these roles through the active platform appearance and system controls.

| Token role | Native source | Use |
| --- | --- | --- |
| `surface.primary` | System window/background surface | Settings and status background |
| `surface.grouped` | System grouped settings surface | Related connection and permission rows |
| `text.primary` / `text.secondary` | System label roles | Title, value, explanation, and helper text |
| `action.primary` | System blue action accent | Primary action, link, selected control, and visible focus |
| `status.success` / `status.warning` / `status.error` / `status.info` | System semantic status colors | Status badge support only; paired with text |
| `border.separator` | System separator role | Native grouped-row separation when the platform supplies it |
| `type.ui` | Android system font / macOS system font | All UI text; no downloaded font |
| `space.1` / `space.2` | 4-point / 8-point rhythm | Compact label gaps and standard row/group gaps |
| `radius.control` / `radius.group` | System radii | Native controls and grouped settings; no custom radius scale |

Rules: use no raw hex colors; use no decorative palette; use no gradient, asset, custom icon family, or custom animation. Status color is supplemental to a Korean text label, icon supplied by the system when needed, and programmatic state.

Application identity exception (2026-09-07): the user supplied a cream-and-teal 3D puzzle illustration and explicitly requested its use as the Mac app and Android launcher icon after background removal. Preserve the artwork and remove the orange backdrop with genuine alpha transparency. This shared identity asset is packaged as macOS ICNS and Android density-specific/adaptive icon resources; keep the full object inside Android's adaptive safe zone. Use a light cream adaptive background to prevent Android from filling transparent backgrounds with black; the shared master and legacy resources remain transparent. macOS can apply its own system frame around the ICNS. It does not change the native status/action icon system or control colors.

## 4. Platform Branches

### Android — Material and system controls

- Use Material/system settings patterns: a status header, grouped preference-like rows, labeled secure fields, and native primary/secondary actions.
- Use the resolved Material system blue action role for the one action accent; use Android semantic system status colors only.
- Respect Android font scaling, light and dark system appearance, TalkBack traversal, and a minimum 48 dp touch target for every actionable row or control.
- Present a foreground-service state plainly. Permission-required rows identify the required permission and route only to the applicable system settings surface.
- Keep sensitive configuration obscured in the labeled secure field; never display token values in status text, accessibility labels, or error details.

### macOS — AppKit/SwiftUI system controls

- Use AppKit/SwiftUI system controls: menu-bar status item, native menu/status presentation, grouped settings form, standard secure field, and standard buttons.
- Use macOS system blue for the one action accent and system semantic status colors only; preserve the user's light or dark appearance choice.
- The full native control or menu-item frame is the click target; do not shrink utility controls below the platform-recommended clickable target. Pointer affordance never replaces keyboard operation.
- The menu-bar status item exposes current state and opens settings; it does not expose clipboard or notification payload history.
- VoiceOver names status, value, cause, and action in Korean. Secure values remain non-spoken and undisclosed.

## 5. Layout & Responsiveness

- **Spacing:** use the 4/8-point rhythm. Tight label-to-value spacing uses one 4-point step; rows, groups, and action clusters use 8-point increments.
- **Structure:** settings are a vertical stack of grouped sections. The connection summary and start/stop actions are first, followed by feature readiness and permissions, then a collapsible server configuration section. macOS login behavior follows the connection settings. The macOS menu path keeps a single compact status summary before settings.
- **Width and reflow:** Android uses one readable column at narrow width. macOS settings preserve readable labels and reflow action clusters before clipping; long Korean labels wrap rather than overlap or truncate a recovery action.
- **Text scaling:** text scaling may enlarge labels and status explanations without hiding the primary action, producing horizontal overflow, or reducing the minimum target.
- **System geometry:** use platform-provided control heights, radii, separators, and grouped containers. Do not invent cards, shadows, or a parallel layout system.

## 6. Primitives & Components

### connection/status row

- **Structure:** Korean state label, short cause/value, status text/badge, and one contextual next action when useful.
- **States:** disconnected, connecting, connected, authentication failure, TLS failure, permission required, retry/backoff, service stopped.
- **Accessibility:** one concise accessible name announces state and cause; the badge never carries the only meaning.

### labeled secure field

- **Structure:** visible Korean label, secure native input, optional non-sensitive validation helper, and explicit field error.
- **States:** empty, valid, invalid, disabled while connecting, and focus.
- **Accessibility:** associated label, secure-entry trait, Korean validation explanation, and no secret echoing.

### primary action

- **Structure:** one native action button for the safe next step, such as `연결`, `다시 시도`, or `권한 열기`.
- **States:** enabled, disabled with reason, pressed/native feedback, keyboard focus, and progress represented by adjacent status text.
- **Accessibility:** ordinary button semantics; Enter/Space activation where the platform supplies it.

### secondary action

- **Structure:** native secondary button or menu action for `중지`, `설정 보기`, or a non-destructive alternative.
- **States:** enabled, disabled with reason, keyboard focus, and confirmation only when a running service is being stopped.
- **Accessibility:** action label states the effect; it is never icon-only.

### status text/badge

- **Structure:** short Korean text is primary; a semantic system-color badge is secondary.
- **States:** maps exactly to the connection states in Section 7.
- **Accessibility:** announce text, not color; do not rely on badge shape alone.

### grouped settings section

- **Structure:** native grouped container with a Korean section title, ordered rows, and in-context helper/error text.
- **States:** default, permission required, field invalid, and service stopped.
- **Accessibility:** logical reading order, visible focus, and no nested scroll region.

### menu-bar status item

- **Structure:** macOS-only native status item with a stateful title/accessibility label, short status menu content, settings action, and start/stop action where applicable.
- **States:** disconnected, connecting, connected, authentication failure, TLS failure, permission required, retry/backoff, service stopped.
- **Accessibility:** VoiceOver exposes state before action; keyboard navigation reaches every menu command.

## 7. States & Interactions

| State | Korean status text | Meaning | Allowed recovery |
| --- | --- | --- | --- |
| disconnected | `연결 안 됨` | Service is available but no current authenticated connection exists. | `연결` or `다시 시도` |
| connecting | `연결 중` | A bounded connection attempt is in progress. | `중지` when supported; otherwise show that retry is automatic without inventing a countdown. |
| connected | `연결됨` | Current connection is authenticated and TLS-validated. | `중지` or `설정 보기` |
| authentication failure | `인증 실패` | Credentials were rejected; automatic retry must stop until changed. | `설정 보기` |
| TLS failure | `보안 연결 확인 필요` | CA, hostname, or pin validation failed; do not send data. | `설정 보기` |
| permission required | `권한 필요` | A required platform permission or system access is unavailable. | `권한 열기` |
| retry/backoff | `잠시 후 다시 시도` | A retryable failure is waiting; explain that retries happen automatically; show exact timing only if available. | `다시 시도` when safe, or `중지` |
| service stopped | `서비스 중지됨` | The user or system stopped bridge work; it is not connected. | `시작` |

Interaction rules:

- Start/stop actions update the status text before any visual emphasis. Do not report `connected` from a button press alone.
- Use native pressed, focus, and progress behavior only. No custom animation is part of this contract.
- Retrying is bounded and its waiting state is visible; terminal authentication and TLS failures do not loop silently.
- Errors remain next to the affected row and use clear Korean labels. They do not use alerts, toast-only feedback, or color-only meaning.
- Keyboard-only operation includes deterministic tab/focus order, Enter/Space activation for system controls, Escape to dismiss transient native presentation where supported, and a visible focus indicator using the action semantic.

## 8. Accessibility & Adaptive Preferences

- **Appearance:** follow light and dark system appearance. Do not select a separate branded theme.
- **Text scaling:** support Android text scaling and macOS accessibility text sizing; reflow without clipping, overlap, horizontal primary-content scrolling, or inaccessible targets.
- **Reduced motion:** honor reduced motion. Because no custom animation is specified, state changes remain direct and native feedback is not replaced with decorative motion.
- **Screen readers:** TalkBack and VoiceOver receive Korean labels for state, cause, value, and next action. Status changes use the platform-appropriate accessible announcement without repeating secrets.
- **Keyboard-only:** every interactive control, including the menu-bar status item path, is reachable, visibly focused, and operable without a pointer.
- **Targets and contrast:** Android meets the 48 dp minimum touch target. macOS keeps the full native control/menu-item hit area. System semantic colors are paired with text and preserve system contrast handling; no color-only meaning.
- **Cognitive recovery:** one state, one cause, one next action. Keep automatic retry behavior visible, avoid jargon where Korean plain language is available, and never require remembering a previous status to recover.

### Verification Matrix

| Scenario | Platform | Observable pass condition |
| --- | --- | --- |
| Appearance and text scaling | Android and macOS | Light and dark follow system setting; enlarged text keeps labels, actions, and status readable. |
| Screen-reader path | TalkBack and VoiceOver | Connection state, cause, field label, and next action are announced in Korean without secrets. |
| Keyboard-only path | Android hardware keyboard and macOS | Focus order reaches secure configuration, actions, and menu-bar status commands; activation works. |
| Recovery states | Android and macOS | Each Section 7 state has accurate Korean text, non-color indication, and only its allowed recovery. |
| Target and native-control check | Android and macOS | Android actions retain 48 dp targets; macOS uses native control/menu-item hit areas. |
| Sensitive-content check | Android and macOS | Tokens, clipboard text, notification bodies, and private key material are absent from UI, accessibility labels, and errors. |

### Accepted Debt

No accepted design debt. Any implementation limitation affecting a persona, accessibility, or recovery path must be recorded in `.omo/frontend-design/state.md` and explicitly accepted before release.

## 9. Pre-deployment UX revision (2026-09-07)

### Tasks and users

- Returning operator: identify relay state and start or stop within the first screen. “Connected” explicitly refers to the relay, not proof of the other device being online.
- First-time operator: expand connection settings, enter the HTTPS endpoint, trust configuration, and a device token, and receive inline validation and durable save feedback.
- Operator recovering a failure: see a short cause and one relevant action; permission capability rows remain independent of transport status.
- Keyboard, screen-reader, and large-text users: labeled native controls, predictable traversal, wrapping descriptions, scrollable content, and no color-only state.

### Revised components

- **Connection summary:** native title emphasis, relay status, a brief description, and adjacent start/stop actions. Do not add peer-presence indicators or unmeasured last-sync times.
- **Readiness row:** feature/permission label, actual observed value, and an applicable permission action. Separate clipboard capability from notification access and notification delivery state. Unknown or last-observed capability must not be presented as currently ready after stopping.
- **Connection disclosure:** native expandable “서버 연결 설정” section, open for missing credentials or invalid input. Full-width labeled fields keep addresses and long certificate pins readable. Show a non-secret saved-credential indicator; an empty token means keep the stored token. Never prefill the secure input.
- **Save feedback:** validate the HTTPS endpoint and optional 64-hex pin before persisting; save the token before recording other settings. Clear the token only after success. Start uses the entered configuration and the UI makes this save-and-start behavior explicit.
- **Active configuration:** disable fields while the current run owns its settings. Explain “중지 후 변경” visibly and provide a way to stop and edit.
- **Spacing:** expand the shared 4/8-point scale to 16 and 24 for grouped rows and section separation. Android title emphasis uses 24sp; body remains scalable 14sp. macOS uses system title3/headline/body/caption roles.
- **Native interaction:** use system focus, pressed feedback, disclosure, secure input, scrolling, and accessibility semantics. No custom animation or decorative styling.

### Revision verification

Exercise stopped/connected/connecting or retry/error, collapsed/expanded configuration, invalid endpoint and pin, saved-token behavior, start/stop and permission routing. Inspect narrow/default windows, both appearances, and Android enlarged text. Source and accessibility-tree checks cover labels and hidden token values; do not claim full assistive-technology testing unless performed.

## 10. Cloudflare Access service credentials (2026-09-07)

- Extend the existing server connection disclosure with an optional `Cloudflare Access 사용` native toggle. When enabled, show a labeled `Client ID` field and secure `Client Secret` field with a short explanation that these are this device's Access service credentials.
- Keep the relay device token as a separate secure credential. Access authentication and relay authentication are both required when Access is enabled.
- Show whether an Access credential is saved without displaying its secret. An empty secret keeps the saved secret only for the unchanged Client ID. A changed Client ID requires a new secret. Disabling Access sends no Access headers.
- Persist Access credentials in macOS Keychain or Android Keystore encryption; never in ordinary preferences, saved view state, logs, or error text. Clear entered secrets after a successful save. Disable configuration controls during an active connection.
- Missing or invalid enabled credentials produce a field-specific Korean recovery message before connecting. Rejected Access authentication and HTTP redirects stop with an authentication error; requests never follow redirects with credentials.
- Verification covers enabled/disabled/partially entered settings, saved-secret reuse, field masking, restored settings, keyboard access, narrow layout, and the actual protected public endpoint. Browser login is not the native apps' authentication path.

## 11. Notification workspace and photo clipboard (2026-09-07)

The user approved custom Mac banners plus a menu-accessible notification list, Mac-side app blocking with new apps allowed, quiet progress updates and a single completion/failure alert. The user also requested bidirectional photo copying and a less rough UI. These decisions expand the earlier no-history scope: a bounded notification list is now intentional; clipboard history remains absent. Physical Galaxy validation is deferred by the user; use Android AVD and the real Mac, and never treat AVD evidence as Samsung verification.

### Direction and information hierarchy

Preserve the familiar native utility and existing cream/teal puzzle identity. Improve hierarchy through a compact navigation rail, generous content insets, native sidebar/window material, restrained rounded surfaces and source-app artwork. Do not introduce a website, downloaded typeface, decorative animation or new framework. The native design contract remains the token source; the frontend redesign reference supplies audit discipline, not browser-specific styling.

- Mac has four destinations: `개요`, `알림`, `앱별 수신`, `연결 설정`. The menu exposes recent notifications and opens the relevant destination. Existing connection/credential/login controls remain fully functional.
- Overview leads with the shared app icon, clear connection state, one start/stop action, and concise capabilities for text/photos and notifications. A connection state never implies peer compatibility or successful image delivery.
- The notification workspace shows source app icon/name, title, body, time and a real progress indicator when provided. Include explicit empty/search-empty states, item dismissal and clear-all. Blocking belongs in a separate searchable app list.
- Android leads with the same app identity and connection summary. Visually group feature readiness, permissions and advanced server configuration; avoid a wall of equally weighted status labels. Keep all existing controls and48dp targets.

### Added shared primitives and tokens

| Primitive/token | Definition and states |
|---|---|
| `window.workspace` | Mac940×700pt default, minimum780×600; narrower existing settings remain readable through scrolling. Sidebar184pt; content inset24pt. |
| `surface.inset` | Native control/window background layered over sidebar/window material;16pt Mac/20dp Android continuous corners; system separator at hairline opacity when necessary. |
| `identity.app` | Existing app artwork64pt/dp in overview; source application icon36pt in rows and44pt in banners;12pt continuous corner radius; app-name initial/system symbol fallback, never bridge artwork as a foreign app icon. |
| `type.heading` | Mac system largeTitle/title2/headline; Android28sp title/24sp status/14sp body/12sp caption. Numeric progress uses monospaced digits. |
| `space.content` | Existing4/8pt rhythm plus12/16/24/32 for icon gaps, rows, content and sections. |
| `notification.row` | Source identity, content, relative timestamp, explicit close action. Long text wraps; full body can expand instead of horizontal overflow. |
| `notification.progress` | Native determinate/indeterminate ProgressView; numeric percentage accompanies a determinate bar. Active updates replace the same item quietly. Completed/failed have text plus semantic system color; disappearance alone is unknown, not success. |
| `notification.banner` |380pt wide native-material nonactivating panel,16pt inset,16pt continuous radius; source icon/content/progress/close. Size follows content within screen bounds. At most three visible; automatically dismiss after a bounded interval; hover pauses dismissal. No focus theft. |
| `filter.appRow` | Source icon/name/package helper plus native receive toggle. Entire control hit target preserved. New apps allowed. Blocking removes retained content and survives restart. Search and empty states required. |
| `banner.pause` | Visible native toggle in menu/workspace. Explain that custom banners use this setting; do not imply macOS Focus automatically controls them. Session deactivation/screen sleep hides banners. |

### Content, privacy and failure behavior

- An Android-redacted notification retains its application identity and says `휴대폰에서 알림 내용을 숨겼어요` or an equally precise unavailable-content message. Never reconstruct unavailable KakaoTalk/OTP text or treat locking alone as redaction.
- Source notification contents may appear only in the dedicated notification list/banner after delivery. They remain excluded from connection status, diagnostic logs, screenshots used as public evidence and credential controls; all QA uses synthetic content.
- Notification history and app preferences have bounded private local storage, explicit clear/dismiss controls and visible save failures. Blocked notices are acknowledged without storing their bodies in history.
- Photos support original PNG/JPEG up to8MiB within validated image dimensions. Unsupported/oversized values leave the source clipboard unchanged and show a concise actionable capability message; never silently downgrade original quality. Both peers and relay need the upgrade; existing stable deployment remains available during isolated QA.
- Preserve Dark appearance on the host. Native screenshots go directly to task files; warn before visible Mac QA and close owned windows promptly.

### Verification contract

Exercise real native overview/notification/filter/connection surfaces at default and minimum width, Dark and app-scoped Light, long Korean content, empty/error states, keyboard traversal, filter persistence, quiet progress updates and completion/failure. Capture a reusable row/banner state harness before integrating its product screen. AVD covers locked/unlocked ordinary/private/MessagingStyle/redacted fixtures and real bidirectional PNG/JPEG clipboard. Review layout/readability and Korean copy independently using fresh native evidence. Browser/Lighthouse metrics do not apply to AppKit/SwiftUI/Android Views. No unverified pixel, accessibility or Samsung claims.

## 12. Quiet notifications and phone-side app selection (2026-09-08)

The phone is the primary place to choose notification senders, before any notification arrives. This supersedes the earlier Mac-only filtering decision. Existing Mac receive preferences remain additional local controls; changing the phone does not erase them.

- `notification.appSelection`: an Android settings destination with a native Back action, installed app name/package search, selected count, system-app visibility toggle, and a recycled native list. Query inventory locally for this feature only; never send it to the relay. Newly installed apps remain allowed. Hidden system rows retain their preferences. Show loading, empty search, save error, allowed and blocked states; save each toggle immediately with failure feedback.
- `filter.appRow` on Android: reuse native system app artwork at 36dp, wrapping app label plus package helper and native Switch. Use the existing type/body/caption tokens, 4/8/12/16/24 spacing and at least48dp touch targets. The whole row operates one accessible toggle; recycled rows must not change a different app. Loading inventory and icons must not block the connection or clipboard threads. Search persists across rotation; system app visibility is optional, not the sending policy.
- `notification.quietPolicy`: own app, service/system/status maintenance, ordinary persistent background notices and low-importance notices are excluded at capture. Real task progress and its terminal update remain eligible. Charging/battery/storage/volume percentages do not imply task progress. App preferences apply before queueing; no inventory or suppressed content is logged.
- `notification.serviceVisibility`: service status display is optional. Starting the bridge never requests POST_NOTIFICATIONS or treats its denial as a broken connection. A settings action opens Android notification controls to hide the existing status notification. Show actual current visibility, explain that sharing remains active when hidden, and state that Android may retain its separate running-app indicator. Never simulate hiding by using invalid foreground notifications or stopping the log-reading session.
- New-install Android13+ notifications stay off until the operator explicitly enables them in Android settings. Existing installs keep the user's OS permission/channel choice. The bridge's permitted status notification remains silent, does not badge, and alerts at most once.

Verification uses only the API37 AVD, synthetic source notifications, an isolated relay/receiver identity, fresh installed APK and final native captures in light/dark, enlarged text and empty/results/system-list states. Physical Galaxy and production Mac/relay are unchanged. System and task-progress cases must be tested through the actual NotificationListenerService, not just mapper fixtures.
- In the app search screen, temporarily collapse explanatory copy and the system-component visibility control while the software keyboard is open. Keep title, search, count and the actual app list usable on a320dp viewport with enlarged text; restore the help when the keyboard closes. This is layout adaptation, not an animation or change to saved filtering.

## 13. In-app clipboard helper setup (2026-09-13)

The Android app owns the clipboard helper setup. Replace external helper installation, manager and guide destinations with one `클립보드 도우미 설정` entry. Preserve server credentials, notification controls and the distinction between relay connection, helper response and actual clipboard detection. A helper response is not proof that a clipboard item reached another device.

### Tasks, primitives and states

- **First-time operator:** open `개발자 옵션`, enable `무선 디버깅`, arrange Android Settings and the bridge in split screen, then open `페어링 코드로 기기 페어링` and enter its current six-digit code without leaving that dialog. Explain before opening Settings that switching away can close the code dialog. Include the manual Settings route and concise help for enabling developer options.
- **Returning operator:** see whether a connection was saved and whether the helper responds. Offer `연결` or `다시 연결` when a saved connection can be retried, plus a disclosure for a fresh pairing code when recovery needs it. Pairing and starting disable duplicate submissions, keep progress text visible and never reset the existing relay configuration.
- **`helper.setup`:** native Back action, one outer scrolling content column, current status surface, optional saved-connection retry, a grouped code form and an explicit automatic-recovery checkbox. Reuse system surfaces, title/status/body/caption roles, the 4/8/12/16/24/32 spacing tokens and 48dp minimum targets. Buttons and explanatory Korean text wrap; the form scrolls above the keyboard in narrow, split-screen and enlarged-text layouts.
- **`helper.pairingCode`:** labeled numeric password field, exactly six digits, no autofill, personalized keyboard learning, saved view state, content capture or secret echo in status/errors. Clear the field after submission and when the setup screen stops. Use the code only for local pairing; never save it. The optional labeled pairing-port field defaults to automatic discovery when empty. Manual help distinguishes the port inside the pairing dialog from the ordinary wireless-debugging connection port.
- **`helper.recovery`:** requires Android 13 or later. Below Android 13, keep the checkbox unchecked and disabled and explain the Android 13 requirement; basic pairing and manual helper connection remain available on Android 11 and 12. On supported versions, it is unchecked unless previously selected by the operator. State explicitly that enabling it lets this app turn on this phone's wireless debugging and attempt reconnection after reboot, only after the first unlock and when trusted Wi-Fi is available. Device conditions may still require manually enabling wireless debugging or entering a new code. No guaranteed-recovery wording. An explicit bridge Stop also stops the helper and cancels that run's automatic resume intent.

| Helper state | Visible state | Recovery |
| --- | --- | --- |
| Unpaired | `처음 연결 필요` | Current local six-digit pairing code |
| Pairing | `코드 확인 중` | Keep the Android pairing dialog open; submissions disabled |
| Starting | `도우미 연결 중` | Wait for the bounded attempt; submissions disabled |
| Ready | `도우미 연결됨` | Inspect actual clipboard detection separately on the home screen |
| Wi-Fi required | `Wi-Fi 연결 필요` | Join a trusted Wi-Fi network, then retry |
| Debugging required | `무선 디버깅 확인 필요` | Open developer options, enable wireless debugging, then retry |
| Pairing required | `새 페어링 코드 필요` | Open a new pairing dialog and enter its current code |
| Error | `도우미 연결 오류` | Generic retry; a fresh-code path remains available |
| Stopped | `도우미 중지됨` | Connect using the saved connection, or pair if none exists |
| Unsupported | `지원되지 않는 Android 버전` | Explain the Android 11 minimum; disable setup operations |

### Accessibility, privacy and verification

Use visible Korean labels, label-to-input associations, native password semantics, deterministic focus order and text-backed status updates. Validation errors remain beside their affected inputs, while connection failures remain beside the submission. Request the invalid field's focus without repeating the code. Support light/dark appearance, large text, software/hardware keyboards and split screen through native reflow; no custom motion or overlay is introduced.

Helper setup adds no notification permission request, ongoing notification, overlay permission or clipboard capture window. Helper failures leave the running relay and existing configuration intact. Fresh native QA must cover first setup, invalid code/port, busy/error/retry, saved/ready/stopped, unsupported Android, settings return and Back, dark/light and enlarged text with the keyboard. Verify that submitted codes are absent from saved state and diagnostics. Browser/React/Lighthouse checks do not apply. Do not claim physical-device pairing, reboot recovery or assistive-technology behavior from source or build evidence alone.
