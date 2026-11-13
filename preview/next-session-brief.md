# Brief: keep only the black theme (for a session opened in the main folder)

Paste this into a new Claude Code session whose folder is
`C:\Users\<you>\Desktop\ovpn-pin-local` (no worktree).

---

Work directly in `C:\Users\<you>\Desktop\ovpn-pin-local` (branch `feat/midnight-redesign`). It holds my uncommitted Midnight redesign: never discard, stash, reset or commit it. Before editing, copy `app/ui/app.css`, `app/ui/midnight.css`, `app/ui/app.js` and `app/ui/index.html` to a backup folder in your scratchpad.

Background: I'm reworking the Relay app (Windows UI in `app/ui`, Android in `android/`) section by section and watch it live in a review desk. `preview/serve.py` serves `app/ui` with `preview/mock.js` standing in for the Python backend (sample data; nothing connects), and `preview/shell.html` frames the 400×740 window with 23 sections by hash id and reloads by itself when a file changes. Decided so far: the new name is Mirage and the logo is the "astrolabe eye" (`preview/logo/mirage-eye.svg`); neither is applied in code yet. Only the black theme stays. Change nothing beyond what I ask, and answer me in Persian.

1. Move the review desk here: copy `C:\Users\<you>\Desktop\ovpn-pin-local\.claude\worktrees\charming-merkle-8d3903\preview\` (`serve.py`, `mock.js`, `shell.html`, `logo/`) into `preview/` in this folder. Add a `relay-preview` config to `.claude/launch.json` that runs `python preview/serve.py 8766`, start it with preview_start and open `http://localhost:8766/#prefs-menu`.

2. Keep only one theme, the Midnight look on a black page:
   - `app/ui/index.html`: delete the `<script>` right after the comment "The theme before the first paint" (it reads `relay.theme` from localStorage). Delete the Settings menu row `data-goto="look"`. Delete `<section class="screen" data-screen="look">` and its contents. The comment above it describes the Window screen, so keep that comment.
   - `app/ui/app.js`: delete `if (info.theme) applyTheme(info.theme);` (about line 2575). Delete the `/* ---- themes */` block (`THEMES`, `themeGlider`/`glideTheme`, `applyTheme` and the IIFE that builds `#themes`), which ends just before `/* Segmented controls.`.
   - `app/ui/midnight.css`: change `:root:not([data-theme])` (line 17) to `:root`, with `--bg: #060607; --deep: 6 6 7; --sheet-glass: rgba(6, 6, 7, 0.92);` and the other Midnight tokens kept. Change `:root:not([data-theme]) body::before` to `:root body::before`. Delete the `:root[data-theme='white']` block (line 52) and `:root[data-theme='white'] .power__disc` (about line 203).
   - `app/ui/app.css`: delete every `:root[data-theme='white']` rule (block at about lines 84-115, then 4383-4386, 4554, 5418, 6246-6251, 6407-6411 and 6469) and both `:root[data-theme='black']` rules (119-123 and 6405).
   - Leave `atlas.js`, `main.py`'s `setTheme` and the `.theme`/`.themes` CSS alone.
   - Review desk: in `preview/shell.html`, remove the app-theme switcher (the `#themes` segmented control, its label and its JS). In `preview/mock.js`, set `theme: 'black'` and drop the branch of the message handler that handles `m.theme`/`applyTheme`.

3. Check it: visit every section of the desk via `location.hash` and confirm `window.__errs` in the `#app` iframe stays empty. Settings must have no Look row, and the page background must be #060607. Show me a screenshot.

4. Then ask me whether Android should get the same change (`android/.../ui/theme/Theme.kt`). Android only builds on GitHub Actions, so ask before pushing.
