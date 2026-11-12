#!/usr/bin/env bash
# Put the app on the phone, with the configs and credentials it needs, and
# then show what it says. Configs go to shared storage, where a file manager
# can see them; the credential goes to the app's private storage (see below).
#
# The debug build carries a .debug suffix on its application id, so its files
# directory is not the one a release build would use. Getting that wrong
# leaves an app that installs, runs, and says "nothing pinned yet" - which is
# why the id is computed here rather than typed.
set -euo pipefail

# Git Bash would otherwise rewrite the phone's /sdcard paths into Windows
# ones before adb sees them; see setup-phone.sh.
export MSYS2_ARG_CONV_EXCL='/sdcard;/data;/system'
unset MSYS_NO_PATHCONV

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

APK="$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
APP_ID="com.erelay.relay.debug"
FILES="/sdcard/Android/data/$APP_ID/files"

[ -f "$APK" ] || {
    echo "build it first:" >&2
    echo "    ./build-android.sh                       # the Go core" >&2
    echo "    cd ../android && ./gradlew :app:assembleDebug   # the app" >&2
    echo >&2
    echo "or take the one a push already built, off the run in Actions." >&2
    exit 1
}

device=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
[ -n "$device" ] || {
    echo "no phone. Plug it in, allow USB debugging, and check:  adb devices" >&2
    exit 1
}
echo "  phone    $device"

adb install -r "$APK" >/dev/null
echo "  app      installed"

# The directory exists only once the app has run, so make it rather than
# assume it. mkdir -p on a path Android already made is not an error.
adb shell mkdir -p "$FILES/pinned" >/dev/null 2>&1 || true

count=$(ls -1 "$ROOT/pinned"/*.ovpn 2>/dev/null | wc -l)
[ "$count" -gt 0 ] || { echo "nothing pinned in $ROOT/pinned" >&2; exit 1; }
adb push "$ROOT/pinned/." "$FILES/pinned/" >/dev/null
echo "  configs  $count pushed"

# The credential goes into the app's private storage, never onto /sdcard:
# anything on shared storage is readable by anybody with a cable and, on
# older Android, by any app allowed to read storage. run-as reaches the
# private folder without root on a debuggable build, which this is. The app
# moves it into its encrypted store and deletes the file the next time it
# starts (Accounts.adopt), so it is force-stopped here to make that now.
#
# Through stdin rather than as an argument, so the password is never on a
# command line - not this machine's, not the phone's.
[ -f "$ROOT/.ovpn-auth" ] || { echo "no $ROOT/.ovpn-auth" >&2; exit 1; }
if adb shell "run-as $APP_ID sh -c 'umask 077 && mkdir -p no_backup && cat > no_backup/auth'" \
    < "$ROOT/.ovpn-auth" 2>/dev/null; then
    adb shell am force-stop "$APP_ID" >/dev/null 2>&1 || true
    echo "  auth     handed to the app"
else
    echo "  auth     run-as refused - add the account in the app: Settings → Accounts" >&2
fi
# One left on shared storage by an older version of this script goes too.
adb shell rm -f "$FILES/auth" "$FILES/auth-windscribe" >/dev/null 2>&1 || true

echo
echo "  open Relay and press connect. Watching the log - ctrl-c to stop:"
echo
adb logcat -c
# GoLog carries anything the core panics with, which is the half that would
# otherwise close the app in silence.
adb logcat -v time Relay:V GoLog:V '*:S'
