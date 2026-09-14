# Application icon

`app-icon.png` is the shared, transparent RGBA master (1254 × 1254) for the
macOS bundle icon and Android launcher icon. It preserves the user-supplied
cream-and-teal puzzle illustration. Python with Pillow and NumPy removed the
orange backdrop and cast shadow using border-connected color segmentation and
local edge matting; opaque artwork pixels retain their original RGB values.
The source artwork was supplied by the user on 2026-09-07.

Regenerate platform resources on macOS, using built-in `sips` and `iconutil`:

```sh
./continuity-bridge/scripts/generate-app-icons.sh
```

Android foreground images retain the transparent canvas and use an 18% inset
in the adaptive icon resource. Its cream background complements the artwork and
avoids Android rendering transparent adaptive backgrounds as black. The legacy
PNG and shared master retain full transparency. macOS may apply its own system
icon frame to the transparent ICNS. The native menu-bar status symbol and Android
notification small icon remain independent of this launcher artwork.
