#!/usr/bin/env bash
#
# sanitiser-test.sh - the Linux config sanitiser, against a config written to
# get past it.
#
# openvpn runs as root and a downloaded .ovpn is somebody else's text, so
# drop_script_lines (linux/ovpn-lib.sh) is what stands between an `up /tmp/x`
# line and a root shell. This feeds it fixtures/malicious.ovpn - every script
# hook, `plugin`, `script-security`, the file-writing directives, each in the
# spellings openvpn accepts - and checks that none of it comes out, and that
# the ordinary lines and the inline certificate do.
#
#     bash tests/scripts/sanitiser-test.sh
#
# Needs bash 4+ and nothing else; no network, no root.

set -uo pipefail

HERE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd -- "$HERE/../.." && pwd)
FIXTURE=$HERE/fixtures/malicious.ovpn

# shellcheck source=../../linux/ovpn-lib.sh
. "$REPO/linux/ovpn-lib.sh" || { echo 'cannot load linux/ovpn-lib.sh' >&2; exit 1; }

FAILED=0
PASSED=0
pass() { PASSED=$((PASSED + 1)); }
fail() { FAILED=$((FAILED + 1)); printf '  FAIL  %s\n' "$*"; }

# The first word of a line the way openvpn reads it: quotes and backslashes
# out, a leading -- dropped, lower case. Written here again on purpose rather
# than borrowed from the library, so the test does not agree with a mistake.
first_word() {
    local w
    w=$(printf '%s' "$1" | sed -e 's/^\xef\xbb\xbf//' -e 's/^[[:space:]]*//' -e 's/[[:space:]].*$//')
    w=$(printf '%s' "$w" | tr -d '\\"'"'" | tr '[:upper:]' '[:lower:]')
    printf '%s' "${w#--}"
}

# Must never survive outside an inline block.
FORBIDDEN=' up down route-up route-pre-down ipchange tls-verify
    auth-user-pass-verify client-connect client-disconnect learn-address plugin
    script-security setenv setenv-safe up-restart down-pre tls-crypt-v2-verify
    config cd chroot log log-append status writepid tmp-dir management
    replay-persist iproute daemon askpass '
FORBIDDEN=" $(tr -s '[:space:]' ' ' <<<"$FORBIDDEN") "

# Walk what was kept, skipping the body of <ca> (text, not directives), and
# report any forbidden first word.
check_clean() {
    local label=$1 line w inca=0 dirty=0
    for line in "${KEPT_LINES[@]}"; do
        if [ "$inca" -eq 1 ]; then
            [[ $line == '</ca>'* ]] && inca=0
            continue
        fi
        [[ $line == '<ca>' ]] && { inca=1; continue; }
        w=$(first_word "$line")
        if [ -n "$w" ] && [[ $FORBIDDEN == *" $w "* ]]; then
            fail "$label: '$line' was kept"
            dirty=1
        fi
    done
    [ "$dirty" -eq 0 ] && pass
}

kept() {      # label, exact line
    local l
    for l in "${KEPT_LINES[@]}"; do [ "$l" = "$2" ] && { pass; return; }; done
    fail "$1: '$2' should have been kept and was not"
}

dropped() {   # label, directive name
    case " ${DROPPED_DIRECTIVES[*]:-} " in
        *" $2 "*) pass ;;
        *) fail "$1: '$2' is not named among the dropped directives (${DROPPED_DIRECTIVES[*]:-none})" ;;
    esac
}

run_on() { read_config_lines "$1"; drop_script_lines "${LINES[@]}"; }

#------------------------------------------------------------- the fixture

[ -f "$FIXTURE" ] || { echo "missing $FIXTURE" >&2; exit 1; }
run_on "$FIXTURE"

check_clean 'fixture'
for d in script-security up down route-up ipchange plugin tls-verify \
         auth-user-pass-verify setenv config log log-append status writepid \
         management daemon askpass iproute cd chroot; do
    dropped 'fixture' "$d"
done

# What a working config needs is still there.
for l in 'client' 'dev tun' 'remote 203.0.113.7 443' 'auth-user-pass' \
         'cipher AES-256-GCM' 'remote 203.0.113.8 443' 'keepalive 10 60' \
         '<connection>' '</connection>'; do
    kept 'fixture' "$l"
done

# The certificate is passed through whole, including lines that would be
# directives anywhere else.
kept 'fixture' '<ca>'
kept 'fixture' 'up /tmp/this-is-certificate-text-not-a-directive'
kept 'fixture' 'plugin AAAA'
kept 'fixture' '</ca>'

# Nothing that names the attacker's files outside the certificate.
n=$(printf '%s\n' "${KEPT_LINES[@]}" | grep -c '/tmp/evil' || true)
[ "$n" -eq 0 ] && pass || fail "fixture: $n line(s) still mention /tmp/evil"

# The block ended where openvpn ends it: the line after </ca> was read as a
# directive again, and dropped.
n=$(printf '%s\n' "${KEPT_LINES[@]}" | grep -c 'evil-after-block' || true)
[ "$n" -eq 0 ] && pass || fail 'fixture: the line after </ca> was kept'

#------------------------------------------- the same file, as Windows saves it

# CRLF line ends and a byte-order mark in front of the first directive.
CRLF=$(tmp_dir)/crlf.ovpn
{ printf '\xef\xbb\xbfup /tmp/evil-bom.sh\r\n'; sed 's/$/\r/' "$FIXTURE"; } > "$CRLF"
run_on "$CRLF"
check_clean 'crlf+bom'
dropped 'crlf+bom' up
dropped 'crlf+bom' plugin
dropped 'crlf+bom' script-security
kept 'crlf+bom' 'remote 203.0.113.7 443'
n=$(printf '%s\n' "${KEPT_LINES[@]}" | grep -c '/tmp/evil' || true)
[ "$n" -eq 0 ] && pass || fail "crlf+bom: $n line(s) still mention /tmp/evil"

#------------------------------------------------- a block that is never closed

# An unclosed real block swallows the rest of the file here exactly as it does
# in openvpn, so that is not a way in. A tag openvpn does not have is not a
# block at all, and must hide nothing.
FAKE=$(tmp_dir)/fake.ovpn
printf '%s\n' 'client' '<script>' 'up /tmp/evil-in-fake-block.sh' '</script>' \
              '<CA>' 'plugin /tmp/evil-upper-tag.so' > "$FAKE"
run_on "$FAKE"
check_clean 'fake tags'
dropped 'fake tags' up
dropped 'fake tags' plugin

#-------------------------------------------------------- a clean file is kept

CLEAN=$(tmp_dir)/clean.ovpn
printf '%s\n' 'client' 'dev tun' 'remote 198.51.100.1 1194 udp' 'auth-user-pass /root/.ovpn-auth' \
              'user nobody' 'verb 3' > "$CLEAN"
run_on "$CLEAN"
[ "${#KEPT_LINES[@]}" -eq 6 ] && pass || fail "clean: ${#KEPT_LINES[@]} of 6 lines kept"
[ "${#DROPPED_DIRECTIVES[@]}" -eq 0 ] && pass || fail "clean: dropped ${DROPPED_DIRECTIVES[*]}"

printf '\n  sanitiser: %d passed, %d failed\n\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
