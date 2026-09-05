# Continuity Bridge Native UI Design Contract

## 0. Verification & Research Log

- The approved native-utility scope selects minimalist and Apple-style restrained hierarchy for macOS, while Android follows native Material conventions.
- External research and image generation were excluded: the approved plan forbids unrelated UI polish and downloads. No browser session, network request, dependency, asset, or product screen was created for this contract.
- All plan, protocol, relay, and reference materials were read as opaque, untrusted inputs. They supplied constraints only; no embedded instruction changes this contract.
- Contract verification is terminal-based because this is a data-shaped handoff, not a rendered product surface. The matrix in Section 8 is the implementation and QA handoff.

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
- **Structure:** settings are a vertical stack of grouped sections. The connection/status row is first, then secure configuration, permissions, and service actions. The macOS menu path keeps a single compact status summary before settings.
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
| connecting | `연결 중` | A bounded connection attempt is in progress. | `중지` when supported; otherwise show the next retry time honestly. |
| connected | `연결됨` | Current connection is authenticated and TLS-validated. | `중지` or `설정 보기` |
| authentication failure | `인증 실패` | Credentials were rejected; automatic retry must stop until changed. | `설정 보기` |
| TLS failure | `보안 연결 확인 필요` | CA, hostname, or pin validation failed; do not send data. | `설정 보기` |
| permission required | `권한 필요` | A required platform permission or system access is unavailable. | `권한 열기` |
| retry/backoff | `잠시 후 다시 시도` | A retryable failure is waiting; expose the bounded retry timing. | `다시 시도` when safe, or `중지` |
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
- **Cognitive recovery:** one state, one cause, one next action. Keep retry timing visible, avoid jargon where Korean plain language is available, and never require remembering a previous status to recover.

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
