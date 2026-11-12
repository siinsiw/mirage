#!/usr/bin/env bash
# Relay tunnel - server installer.
#
#   ./install-server.sh <ip-or-domain> [--password P] [--api-password A]
#                       [--port N] [--ss-port N] [--no-ss] [--slots N]
#                       [--no-verify-exit]
#
# One run sets up everything; the apps write this line themselves, with the
# passwords they already hold, so nothing has to be copied back into them.
#
# A password on the command line lands in the shell's history and, while this
# runs, in the process list. To keep it out of both, leave the option off and
# set RELAY_PASSWORD / RELAY_API_PASSWORD in the environment instead, or give
# `-` as the value (--password -) to read it as one line from stdin; two `-`
# are read in the order they were given. A password is 1 to 128 printable
# ASCII characters and none of ' " ` $ \ { } #, the rule the apps share.
#
# --no-verify-exit keeps the old exit-chain dialer, TLS to the provider with
# its certificate unchecked and no SNI, where the default sends the exit's
# real hostname (the one the app hands over with each exit) and checks the
# certificate against it. Keep the default,
# above all on a server in Iran: measured 2026-11-12 from one, uploads
# through Windscribe exits ran at 1.2-1.9 MB/s with the real hostname and
# about 10 KB/s per connection without it (an unrelated decoy name did not
# help either). Use --no-verify-exit only if connecting through the exit
# fails entirely; and without the check, a man in the middle between here
# and the provider could read the provider credential the chain carries.
# (Surfshark's exits did not complete TLS from that server at all, checked
# or not: their addresses are blocked from Iran, and this flag does not
# change that.)
#
# It only decides the placeholder chains this script writes. The real exit
# chain is the app's: it sends one through the API every time it connects,
# with its own choice of checking, and that replaces whatever is here. So a
# server installed with --no-verify-exit also needs exit checking turned off
# in the app for this server ("check the exit"), or the app will push checked
# chains that cannot complete; the script says so again when it finishes.
#
# It ends by printing a key - relay://... - that carries the server, its
# passwords and its Shadowsocks settings. Pasted into either app it sets the
# whole tunnel up, which is how a server set up by somebody else, or by hand,
# reaches a phone without anybody typing a password into one.
#
# Shadowsocks is the second way in, beside the WebSocket that goes through
# Cloudflare: straight to this server's address, on two ports. The first
# (8388 unless --ss-port says otherwise) exits here; the one after it exits at
# whichever provider location the app has chosen, the same exit-chain the
# multi-IP WebSocket path uses. Straight to the address is the quicker of the
# two ways in when the address is reachable, and the only one that counts as
# domestic traffic when this server is inside Iran.
#
# It also keeps locations for other devices - an iPhone, a friend's laptop:
# anything with a V2Ray client and no Relay (--slots, 8 unless told). Each is
# a user on sing-box, reached by VLESS on /vl or VMess on /vm through the same
# nginx, and routed to its own gost chain, loc-<k>, which the app points at a
# provider location and saves. Slot 0 exits here. The users' UUIDs are derived
# from one id kept in /etc/relay/export.id, which the key carries, so the app
# can make any slot's link itself.
#
# An IP address is the ordinary case: no domain, no DNS, no CA. The server
# gets a self-signed certificate for that address and the apps pin it the
# first time they connect. A domain still works as before - a Let's Encrypt
# certificate, and Cloudflare in front if it is proxied there.
#
# The provider exit for server + exit is not set here. The app sends the
# location it was asked for, with that provider's own credential, through
# the API each time it connects - so no account ever goes into this line.
#
# --port moves the TLS door off 443, for a server where something else
# already answers there - an Xray, another site. The key carries the port, so
# the apps dial it; 443 is left out of the key, as it always was. Through
# Cloudflare it has to be one of the ports Cloudflare proxies (8443, 2053,
# 2083, 2087, 2096); straight to the address any port will do.
#
# Idempotent: safe to re-run. Leaves any other nginx site alone - the block it
# writes carries its own server_name. Passwords already on the server are
# kept unless new ones are passed.
set -euo pipefail
# Everything this writes - the passwords, the gost and sing-box configs, the
# key - is a secret, so nothing is ever created readable by others, not even
# for the moment between writing a file and chmod-ing it. What has to be
# readable (nginx's site, the unit files) is opened up by name further down.
umask 077

DOMAIN=""
NEWPW="${RELAY_PASSWORD:-}"
NEWAPW="${RELAY_API_PASSWORD:-}"
TLSPORT=443
SSPORT=8388
WITH_SS=1
SLOTS=8
VERIFY_EXIT=1
USAGE="usage: $0 <ip-or-domain> [--password P|-] [--api-password A|-] [--port N] [--ss-port N] [--no-ss] [--slots N] [--no-verify-exit]
  --no-verify-exit  only the placeholder exit chains written here skip the
                    provider certificate check; the app pushes its own chain
                    when it connects, so turn exit checking off in the app
                    for this server too. Checking is what keeps upload fast
                    from a server in Iran (without it, about 10 KB/s), so use
                    this only if connecting through the exit fails entirely.
                    Passwords given as - are read from stdin;
                    RELAY_PASSWORD / RELAY_API_PASSWORD also work."
# `-` reads the value as one line from stdin, so it never sits in argv.
from_stdin() { local v; IFS= read -r v || [ -n "$v" ] || { echo "$1: nothing on stdin" >&2; exit 1; }; printf '%s' "$v"; }
while [ $# -gt 0 ]; do
  case "$1" in
    --password)     [ $# -ge 2 ] || { echo "$USAGE" >&2; exit 1; }
                    NEWPW="$2"; [ "$NEWPW" = - ] && NEWPW=$(from_stdin "$1"); shift 2 ;;
    --api-password) [ $# -ge 2 ] || { echo "$USAGE" >&2; exit 1; }
                    NEWAPW="$2"; [ "$NEWAPW" = - ] && NEWAPW=$(from_stdin "$1"); shift 2 ;;
    --port)         TLSPORT="${2:-}"; shift 2 ;;
    --ss-port)      SSPORT="${2:-}"; shift 2 ;;
    --no-ss)        WITH_SS=0; shift ;;
    --slots)        SLOTS="${2:-}"; shift 2 ;;
    --no-verify-exit) VERIFY_EXIT=0; shift ;;
    -h|--help)      echo "$USAGE"; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 1 ;;
    *)  DOMAIN="$1"; shift ;;
  esac
done
[ -n "$DOMAIN" ] || { echo "$USAGE" >&2; exit 1; }

# The shared password rule: 1-128 characters, printable ASCII (0x21-0x7E),
# and none of ' " ` $ \ { } # - the characters that mean something to a
# shell, YAML or JSON. Checked a character at a time, in the C locale so a
# multi-byte character shows up as bytes above 0x7E and is refused.
pw_ok() {
  local LC_ALL=C p="$1" i c o
  [ "${#p}" -ge 1 ] && [ "${#p}" -le 128 ] || return 1
  for ((i = 0; i < ${#p}; i++)); do
    c="${p:i:1}"
    printf -v o '%d' "'$c"
    [ "$o" -ge 33 ] && [ "$o" -le 126 ] || return 1
    case "$c" in \'|\"|\`|\$|\\|\{|\}|\#) return 1 ;; esac
  done
}
for pair in "--password:$NEWPW" "--api-password:$NEWAPW"; do
  opt="${pair%%:*}" val="${pair#*:}"
  if [ -n "$val" ] && ! pw_ok "$val"; then
    echo "${opt}: a password is 1 to 128 printable ASCII characters, none of ' \" \` \$ \\ { } #" >&2
    exit 1
  fi
done
unset pair opt val

# The address goes into nginx's config, a file name and the key, so it is an
# IPv4 address or a hostname made of letters, digits and hyphens, nothing else.
LC_ALL=C
if [[ "$DOMAIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  IFS=. read -r o1 o2 o3 o4 <<<"$DOMAIN"
  for o in "$o1" "$o2" "$o3" "$o4"; do
    [[ "$o" =~ ^(0|[1-9][0-9]{0,2})$ ]] && [ "$o" -le 255 ] \
      || { echo "not an IPv4 address: $DOMAIN" >&2; exit 1; }
  done
  unset o o1 o2 o3 o4
elif [ "${#DOMAIN}" -gt 253 ] \
    || ! [[ "$DOMAIN" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)*[A-Za-z]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$ ]]; then
  echo "not a hostname: $DOMAIN (letters, digits and hyphens, in dot-separated labels)" >&2
  exit 1
fi
[[ "$TLSPORT" =~ ^[0-9]+$ ]] && [ "$TLSPORT" -ge 1 ] && [ "$TLSPORT" -le 65535 ] \
  || { echo "--port wants a number from 1 to 65535" >&2; exit 1; }
[[ "$SLOTS" =~ ^[0-9]+$ ]] && [ "$SLOTS" -le 32 ] \
  || { echo "--slots wants a number from 0 to 32" >&2; exit 1; }
[[ "$SSPORT" =~ ^[0-9]+$ ]] && [ "$SSPORT" -ge 1 ] && [ "$SSPORT" -le 65534 ] \
  || { echo "--ss-port wants a number from 1 to 65534" >&2; exit 1; }
# The exit port is always the one after, so a key never has to say both and
# a person reading a firewall rule can see they belong together.
SSXPORT=$((SSPORT + 1))
SSMETHOD=chacha20-ietf-poly1305

# An address rather than a name: no certificate authority will vouch for it
# without a name, so the certificate is this server's own.
IS_IP=0
if [[ "$DOMAIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then IS_IP=1; fi

GOST_VER=3.3.0
# The phone's half. gost carries the desktop, but no phone client speaks a
# proxy over WebSocket - sing-box's own `http` outbound has a path field and
# it is the path of an HTTP request, not an upgrade - so the same server also
# offers the one language they all do speak. The same version the desktop
# ships (SINGBOX_VERSION in .github/workflows/desktop-release.yml).
SB_VER=1.14.4
# The release archives' SHA-256, pinned here so a swapped download is refused
# before it is ever run as anything. From the projects' own release pages:
#   https://github.com/go-gost/gost/releases/download/v3.3.0/checksums.txt
#   https://github.com/SagerNet/sing-box/releases/tag/v1.14.4 (the digest
#   GitHub shows beside each asset)
# A new version means new sums here, never a skipped check.
declare -A GOST_SHA256=(
  [amd64]=676fb7f78d267b6ae73df719c0c7f2b565dde7147da935cfafbc1e1da558b6d5
  [arm64]=d03699e3f385d4ff5dad68046712adfcc7515325a064d2ab046e0bece30f8f8f
)
declare -A SB_SHA256=(
  [amd64]=699a1b510aa4396b2176d32d1181920399ebf1e267835da6f3b598d0f7c24968
  [arm64]=436441f76ee2f7fe21b5b2d426d3cb261a480455a704865789ad533e4273ec26
)
CFG=/etc/gost/config.yaml
SBCFG=/etc/sing-box/config.json
SITE="/etc/nginx/sites-available/relay-${DOMAIN}.conf"
# gost and sing-box run as this account, not as root: gost's API can be told
# to write its config anywhere, and the unit lets this account write nowhere
# but /etc/gost.
RUNAS=relay

say() { printf '\n=== %s\n' "$*"; }

# A value for YAML or JSON, double-quoted with its backslashes and quotes
# escaped. Both read "\\" and "\"" the same way, so one function does for
# both; the values are single-line ASCII by the time they get here.
dq() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; printf '"%s"' "$s"; }

# nginx -t on its own in an && list would not stop the script under set -e,
# and a reload of a configuration nginx refused leaves the old one running
# with nobody told. This prints nginx's own complaint and stops.
nginx_ok() {
  local out
  if ! out=$(nginx -t 2>&1); then
    printf '%s\n' "$out" >&2
    echo "nginx refused the configuration above; nothing was reloaded" >&2
    exit 1
  fi
}

case "$(uname -m)" in
  x86_64|amd64)  ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "no gost / sing-box build pinned for $(uname -m); amd64 and arm64 only" >&2; exit 1 ;;
esac

# Downloads go to a directory of their own, made fresh and private, and gone
# when this exits however it exits - not a fixed name in /tmp that somebody
# else could have put there first.
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

fetch_verified() {  # $1 = url, $2 = file, $3 = sha256
  curl -fsSL --proto '=https' --tlsv1.2 -o "$2" "$1"
  if ! printf '%s  %s\n' "$3" "$2" | sha256sum -c --quiet - >/dev/null 2>&1; then
    echo "checksum mismatch for $1" >&2
    echo "  expected $3" >&2
    echo "  got      $(sha256sum "$2" | cut -d' ' -f1)" >&2
    exit 1
  fi
}

say "packages"
export DEBIAN_FRONTEND=noninteractive
# The packages' own scripts make directories nginx's workers have to read, so
# they run with the usual umask, not this script's.
(
  umask 022
  apt-get update -qq
  if [ "$IS_IP" = 1 ]; then
    apt-get install -y -qq nginx curl openssl >/dev/null
  else
    apt-get install -y -qq nginx certbot curl >/dev/null
  fi
)

say "gost ${GOST_VER} (${ARCH})"
if [ "$(gost -V 2>/dev/null | grep -o "v${GOST_VER}" || true)" != "v${GOST_VER}" ]; then
  fetch_verified \
    "https://github.com/go-gost/gost/releases/download/v${GOST_VER}/gost_${GOST_VER}_linux_${ARCH}.tar.gz" \
    "$WORK/gost.tgz" "${GOST_SHA256[$ARCH]}"
  tar xzf "$WORK/gost.tgz" -C "$WORK" gost
  install -m755 "$WORK/gost" /usr/local/bin/gost
fi
gost -V

say "sing-box ${SB_VER} (${ARCH})"
if ! sing-box version 2>/dev/null | head -1 | grep -qF "${SB_VER}"; then
  fetch_verified \
    "https://github.com/SagerNet/sing-box/releases/download/v${SB_VER}/sing-box-${SB_VER}-linux-${ARCH}.tar.gz" \
    "$WORK/sb.tgz" "${SB_SHA256[$ARCH]}"
  tar xzf "$WORK/sb.tgz" -C "$WORK"
  install -m755 "$WORK/sing-box-${SB_VER}-linux-${ARCH}/sing-box" /usr/local/bin/sing-box
fi
sing-box version | head -1

say "account ${RUNAS}"
# A system account with no home and no shell, made once. An install from
# before this ran both as root; the files they read change hands below and
# the units are rewritten, so running this again is the whole migration.
if ! id -u "$RUNAS" >/dev/null 2>&1; then
  useradd --system --user-group --no-create-home --home-dir /nonexistent \
    --shell /usr/sbin/nologin "$RUNAS"
fi

say "secrets"
# /etc/gost is root's, and the account can only look in it. The one file it
# owns is config.yaml: the app's saves go through gost's API, which truncates
# and rewrites that file in place (os.Create), so owning the file is enough
# and the directory need not be writable. That way it can never put a link
# or a file of its own where root writes - the passwords here stay root's,
# readable by root alone. /etc/relay is root's only.
mkdir -p /etc/gost /etc/sing-box /etc/relay
chown root:"$RUNAS" /etc/gost /etc/sing-box
chmod 0750 /etc/gost /etc/sing-box
chown root:root /etc/relay
chmod 0700 /etc/relay
newpw() { head -c 18 /dev/urandom | base64 | tr -d '/+=' | head -c 20; }
# The app's own passwords when it sent them, so it already knows them.
if [ -n "$NEWPW" ];  then printf '%s' "$NEWPW"  > /etc/gost/tunnel.pw; fi
if [ -n "$NEWAPW" ]; then printf '%s' "$NEWAPW" > /etc/gost/api.pw; fi
[ -s /etc/gost/tunnel.pw ] || newpw > /etc/gost/tunnel.pw
[ -s /etc/gost/api.pw ]    || newpw > /etc/gost/api.pw
# Kept beside the passwords and generated once, so a phone that already holds
# a config keeps working when this is run again.
[ -s /etc/gost/vless.uuid ] || sing-box generate uuid > /etc/gost/vless.uuid
# Shadowsocks has one key for everybody who connects, so it is its own and not
# the tunnel password: a key handed to a phone should not also be the
# password of the WebSocket paths.
[ -s /etc/gost/ss.pw ] || newpw > /etc/gost/ss.pw
# The id the exported locations' UUIDs are derived from. Kept, like the
# rest, so links already handed out keep working when this runs again.
[ -s /etc/relay/export.id ] || newpw > /etc/relay/export.id
chmod 600 /etc/gost/*.pw /etc/gost/vless.uuid /etc/relay/export.id
PW=$(cat /etc/gost/tunnel.pw)
XID=$(cat /etc/relay/export.id)
if [ "$SLOTS" -gt 0 ] && ! command -v python3 >/dev/null 2>&1; then
  # The UUIDs are uuid5s, which is one line of Python and no line of bash.
  echo "  python3 is missing, so no locations for other devices this time"
  SLOTS=0
fi
APW=$(cat /etc/gost/api.pw)
UUID=$(cat /etc/gost/vless.uuid)
SSPW=$(cat /etc/gost/ss.pw)

# The exit node's account is the app's to send (see the top). Until it does,
# multi-IP mode points at a placeholder that refuses, which is the honest
# thing for it to do with no account.
SSU=CHANGEME ; SSP=CHANGEME

if [ "$IS_IP" = 1 ]; then
  say "certificate (self-signed, for ${DOMAIN})"
  # Ten years, because nothing renews it: the apps trust this exact
  # certificate, not a CA, so a new one would only make them refuse.
  CERT=/etc/relay/cert.pem
  KEY=/etc/relay/key.pem
  mkdir -p /etc/relay
  if [ ! -s "$CERT" ] || ! openssl x509 -in "$CERT" -noout -ext subjectAltName 2>/dev/null | grep -q "IP Address:${DOMAIN}\$"; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
      -days 3650 -subj "/CN=${DOMAIN}" \
      -addext "subjectAltName=IP:${DOMAIN}" \
      -addext "extendedKeyUsage=serverAuth" \
      -keyout "$KEY" -out "$CERT" 2>/dev/null
    chmod 600 "$KEY"
  fi
  PIN=$(openssl x509 -in "$CERT" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')
  : > "$SITE"
else
  say "nginx :80 (needed for the certificate)"
  # nginx's workers read the challenge files, so this one is opened up.
  install -d -m 0755 /var/www/certbot
  cat > "$SITE" <<NGX
server {
    listen 80;
    listen [::]:80;
    server_name ${DOMAIN};
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 404; }
}
NGX
  chmod 644 "$SITE"
  ln -sf "$SITE" /etc/nginx/sites-enabled/
  nginx_ok
  systemctl reload nginx

  say "certificate"
  if [ ! -d "/etc/letsencrypt/live/${DOMAIN}" ]; then
    ( umask 022
      certbot certonly --webroot -w /var/www/certbot -d "${DOMAIN}" \
        --non-interactive --agree-tos --register-unsafely-without-email )
  fi
  CERT="/etc/letsencrypt/live/${DOMAIN}/fullchain.pem"
  KEY="/etc/letsencrypt/live/${DOMAIN}/privkey.pem"
  PIN=""
  # certbot renews on its own timer but nothing told nginx, which kept
  # serving the old certificate until something else reloaded it. Only nginx
  # reads the certificate - gost and sing-box sit behind it on plain
  # WebSockets - so a reload is all a renewal needs.
  mkdir -p /etc/letsencrypt/renewal-hooks/deploy
  cat > /etc/letsencrypt/renewal-hooks/deploy/relay-nginx-reload.sh <<'HOOK'
#!/bin/sh
# Written by the Relay installer: pick up a renewed certificate.
nginx -t >/dev/null 2>&1 && systemctl reload nginx
HOOK
  chmod 755 /etc/letsencrypt/renewal-hooks/deploy/relay-nginx-reload.sh
fi

say "nginx :${TLSPORT}"
ws_block() {  # $1 = path, $2 = upstream port
cat <<NGX
    location /$1 {
        proxy_pass http://127.0.0.1:$2;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host \$host;
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
NGX
}
{
  cat <<NGX
server {
    listen ${TLSPORT} ssl;
    listen [::]:${TLSPORT} ssl;
    server_name ${DOMAIN};
    ssl_certificate     ${CERT};
    ssl_certificate_key ${KEY};

NGX
  ws_block gw  10000     # single-IP mode  - exits at this server
  ws_block ex  10001     # multi-IP mode   - exits at the chosen Surfshark exit
  ws_block gwb 10002     # bulk, no multiplexing
  ws_block vl  10003     # the phone's door: VLESS over the same WebSocket
  if [ "$SLOTS" -gt 0 ]; then
    ws_block vm 10004    # other devices: VMess, beside the VLESS above
  fi
  # The API is public - the apps reach it the way they reach the tunnel - so
  # it is rate-limited per address: enough for an app's few calls when it
  # connects, too few to guess the API password at any useful speed.
  cat <<NGX
    location /api/ {
        limit_req zone=relay_api burst=20 nodelay;
        limit_req_status 429;
        proxy_pass http://127.0.0.1:18080/api/;
        proxy_set_header Host \$host;
    }
    location / { return 404; }
}
NGX
} >> "$SITE"
chmod 644 "$SITE"
# The zone lives in conf.d, read once inside http {}, so a second relay site
# on the same box does not declare it twice.
cat > /etc/nginx/conf.d/relay-api-limit.conf <<'NGX'
# Written by the Relay installer: the rate limit on each relay site's /api/.
limit_req_zone $binary_remote_addr zone=relay_api:1m rate=5r/s;
NGX
chmod 644 /etc/nginx/conf.d/relay-api-limit.conf
ln -sf "$SITE" /etc/nginx/sites-enabled/
nginx_ok
systemctl enable --now nginx >/dev/null 2>&1 || true
systemctl reload nginx

say "gost"
# Every password and name goes into the YAML double-quoted and escaped (dq),
# so a value can only ever be a value, never more YAML.
Q_PW=$(dq "$PW"); Q_APW=$(dq "$APW"); Q_SSPW=$(dq "$SSPW")
Q_SSU=$(dq "$SSU"); Q_SSP=$(dq "$SSP"); Q_SSM=$(dq "$SSMETHOD")
# The dialer to the provider. Checked by default: the placeholder below has
# no name to check against and so refuses, which it does anyway, and the
# apps replace it with the real exit through the API when they connect.
# --no-verify-exit keeps the old unchecked form (see the top).
if [ "$VERIFY_EXIT" = 1 ]; then
  EXIT_TLS="{secure: true}"
else
  EXIT_TLS="{secure: false}"
fi
# Not behind nginx: Shadowsocks is its own encryption on a bare TCP port, and
# going straight to the address is the point of it. One key for both ports.
# TODO: chacha20-ietf-poly1305 is the original AEAD Shadowsocks; moving to a
# 2022 cipher has to happen together with the apps, which build the client.
SS_SERVICES=""
if [ "$WITH_SS" = 1 ]; then
  SS_SERVICES=$(cat <<YAML

  - name: ss-single
    addr: ":${SSPORT}"
    handler: {type: ss, auth: {username: ${Q_SSM}, password: ${Q_SSPW}}}
    listener: {type: tcp}

  - name: ss-multi
    addr: ":${SSXPORT}"
    handler: {type: ss, chain: exit-chain, auth: {username: ${Q_SSM}, password: ${Q_SSPW}}}
    listener: {type: tcp}
YAML
)
fi
# One gost service and chain per exported location: sing-box hands each
# slot's user to its service, and the app points the chain at a provider
# exit and saves the file. Until it does, each refuses, like exit-chain.
LOC_SERVICES=""
LOC_CHAINS=""
for k in $(seq 1 "$SLOTS"); do
  LOC_SERVICES="${LOC_SERVICES}
  - name: loc-${k}
    addr: \"127.0.0.1:$((10100 + k))\"
    handler: {type: http, chain: loc-${k}, auth: {username: relay, password: ${Q_PW}}}
    listener: {type: tcp}
"
  LOC_CHAINS="${LOC_CHAINS}
  - name: loc-${k}
    hops:
      - name: exit
        nodes:
          - name: current
            addr: 146.70.194.221:443
            connector:
              type: http
              auth:
                username: ${Q_SSU}
                password: ${Q_SSP}
            dialer:
              type: tls
              tls: ${EXIT_TLS}
"
done
# Written as root into a file the gost account owns (so the app's saves
# through the API can rewrite it), in a directory that account cannot write,
# so it cannot have swapped the file for a link to somewhere else.
cat > "$CFG" <<YAML
api:
  addr: "127.0.0.1:18080"
  pathPrefix: /api
  accesslog: false
  auth:
    username: relay
    password: ${Q_APW}

services:
  - name: single
    addr: "127.0.0.1:10000"
    handler: {type: http, auth: {username: relay, password: ${Q_PW}}}
    listener: {type: mws, metadata: {path: /gw}}

  - name: multi
    addr: "127.0.0.1:10001"
    handler: {type: http, chain: exit-chain, auth: {username: relay, password: ${Q_PW}}}
    listener: {type: mws, metadata: {path: /ex}}

  - name: bulk
    addr: "127.0.0.1:10002"
    handler: {type: http, auth: {username: relay, password: ${Q_PW}}}
    listener: {type: ws, metadata: {path: /gwb}}
${SS_SERVICES}
${LOC_SERVICES}

chains:
  - name: exit-chain
    hops:
      - name: exit
        nodes:
          - name: current
            addr: 146.70.194.221:443
            connector:
              type: http
              auth:
                username: ${Q_SSU}
                password: ${Q_SSP}
            dialer:
              type: tls
              tls: ${EXIT_TLS}
${LOC_CHAINS}

log:
  level: info
YAML
chown "$RUNAS":"$RUNAS" "$CFG"
chmod 600 "$CFG"

say "sing-box"
# No TLS here and none wanted: nginx has already terminated it, and behind
# that Cloudflare terminated the one the phone actually made. This end only
# has to be the far side of the WebSocket that arrives on /vl.
if [ "$SLOTS" -gt 0 ]; then
  # Written by Python: one user per slot on each door, each routed to its
  # own gost chain, and that is more JSON than a heredoc should be trusted
  # with.
  UUID="$UUID" XID="$XID" N="$SLOTS" PW="$PW" python3 - > "$SBCFG" <<'PY'
import json, os, uuid
e = os.environ
n = int(e['N'])

def users():
    return [{'name': f'slot-{k}',
             'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL,
                                    f"relay-export:{e['XID']}:{k}"))}
            for k in range(n + 1)]

print(json.dumps({
    'log': {'level': 'warn', 'timestamp': True},
    'inbounds': [
        {'type': 'vless', 'tag': 'phone', 'listen': '127.0.0.1',
         'listen_port': 10003,
         'users': [{'name': 'relay', 'uuid': e['UUID']}] + users(),
         'transport': {'type': 'ws', 'path': '/vl'}},
        {'type': 'vmess', 'tag': 'others', 'listen': '127.0.0.1',
         'listen_port': 10004,
         'users': [dict(u, alterId=0) for u in users()],
         'transport': {'type': 'ws', 'path': '/vm'}},
    ],
    'outbounds': [{'type': 'direct', 'tag': 'out'}] + [
        {'type': 'http', 'tag': f'loc-{k}', 'server': '127.0.0.1',
         'server_port': 10100 + k, 'username': 'relay', 'password': e['PW']}
        for k in range(1, n + 1)],
    'route': {
        'rules': [{'auth_user': [f'slot-{k}'], 'outbound': f'loc-{k}'}
                  for k in range(1, n + 1)],
        'final': 'out'},
}, indent=2))
PY
else
cat > "$SBCFG" <<JSON
{
  "log": { "level": "warn", "timestamp": true },
  "inbounds": [
    {
      "type": "vless",
      "tag": "phone",
      "listen": "127.0.0.1",
      "listen_port": 10003,
      "users": [ { "uuid": $(dq "$UUID"), "name": "relay" } ],
      "transport": { "type": "ws", "path": "/vl" }
    }
  ],
  "outbounds": [ { "type": "direct", "tag": "out" } ]
}
JSON
fi
# Root's file, readable by the sing-box account and nobody else; it never
# writes its own config.
chown root:"$RUNAS" "$SBCFG"
chmod 640 "$SBCFG"
sing-box check -c "$SBCFG"

# Both run as ${RUNAS}, sandboxed: no new privileges, the whole file system
# read-only but for what each must write, no home directories, a private
# /tmp, and no capabilities at all - every port they listen on is above 1023
# except a Shadowsocks port chosen below it, which gets the one capability
# that binding it takes.
GOST_CAPS=""
if [ "$WITH_SS" = 1 ] && [ "$SSPORT" -lt 1024 ]; then
  GOST_CAPS="CAP_NET_BIND_SERVICE"
fi
hardening() {  # $1 = the capability, or nothing
cat <<UNIT
User=${RUNAS}
Group=${RUNAS}
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectKernelLogs=yes
ProtectControlGroups=yes
ProtectClock=yes
ProtectHostname=yes
RestrictNamespaces=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes
LockPersonality=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
CapabilityBoundingSet=$1
AmbientCapabilities=$1
UNIT
}

{
cat <<UNIT
[Unit]
Description=Relay tunnel, the phone's half (sing-box)
After=network.target
[Service]
ExecStart=/usr/local/bin/sing-box -D /var/lib/sing-box run -c ${SBCFG}
StateDirectory=sing-box
Restart=always
RestartSec=2
UNIT
hardening ""
cat <<UNIT
[Install]
WantedBy=multi-user.target
UNIT
} > /etc/systemd/system/sing-box.service

# /etc/gost is writable here only so gost's API can save config.yaml, the one
# file in it the account owns; everything else there is root's.
{
cat <<UNIT
[Unit]
Description=Relay tunnel (gost)
After=network.target
[Service]
ExecStart=/usr/local/bin/gost -C ${CFG}
ReadWritePaths=/etc/gost
Restart=always
RestartSec=2
UNIT
hardening "$GOST_CAPS"
cat <<UNIT
[Install]
WantedBy=multi-user.target
UNIT
} > /etc/systemd/system/gost.service
chmod 644 /etc/systemd/system/sing-box.service /etc/systemd/system/gost.service
systemctl daemon-reload
for unit in gost sing-box; do
  systemctl enable --now "$unit" >/dev/null 2>&1 || true
  systemctl restart "$unit"
done
sleep 2

if command -v ufw >/dev/null 2>&1 \
    && ufw status 2>/dev/null | grep -q '^Status: active'; then
  # Only when ufw is already guarding the box: switching a firewall on is not
  # this script's decision, but leaving its own ports shut behind one is.
  say "firewall"
  OPENED="${TLSPORT}/tcp"
  ufw allow "${TLSPORT}/tcp" >/dev/null
  if [ "$WITH_SS" = 1 ]; then
    ufw allow "${SSPORT}/tcp" >/dev/null
    ufw allow "${SSXPORT}/tcp" >/dev/null
    OPENED="${OPENED}, ${SSPORT}/tcp and ${SSXPORT}/tcp"
  fi
  echo "  opened ${OPENED}"
fi

# The key: everything an app needs, in one line to paste. base64url of a JSON
# object so it survives a chat message, a QR code and a terminal that wraps.
# Kept on the server, so it can be shown again without running this again:
#   cat /etc/relay/key
mkdir -p /etc/relay
SSJSON=""
if [ "$WITH_SS" = 1 ]; then
  SSJSON=",\"ss\":{\"method\":$(dq "$SSMETHOD"),\"password\":$(dq "$SSPW"),\"port\":${SSPORT},\"exitPort\":${SSXPORT}}"
fi
if command -v python3 >/dev/null 2>&1; then
  KEY=$(HOST="$DOMAIN" PIN="$PIN" PW="$PW" APW="$APW" SS="$WITH_SS" \
        SSM="$SSMETHOD" SSP="$SSPW" SSPORT="$SSPORT" SSX="$SSXPORT" \
        XID="$XID" SLOTS="$SLOTS" TLSPORT="$TLSPORT" python3 - <<'PY'
import base64, json, os
e = os.environ
key = {'v': 1, 'host': e['HOST'], 'pin': e['PIN'], 'pw': e['PW'], 'apw': e['APW']}
if int(e['TLSPORT']) != 443:
    key['port'] = int(e['TLSPORT'])
if e['SS'] == '1':
    key['ss'] = {'method': e['SSM'], 'password': e['SSP'],
                 'port': int(e['SSPORT']), 'exitPort': int(e['SSX'])}
if int(e['SLOTS']) > 0:
    key['x'] = {'id': e['XID'], 'n': int(e['SLOTS'])}
raw = json.dumps(key, separators=(',', ':')).encode()
print('relay://' + base64.urlsafe_b64encode(raw).decode().rstrip('='))
PY
)
else
  # The address is checked at the top and the pin is hex; the passwords may
  # be the app's own, so they go through dq like every other string.
  PORTJSON=""
  if [ "$TLSPORT" != 443 ]; then PORTJSON=",\"port\":${TLSPORT}"; fi
  RAW="{\"v\":1,\"host\":$(dq "$DOMAIN"),\"pin\":$(dq "$PIN"),\"pw\":$(dq "$PW"),\"apw\":$(dq "$APW")${PORTJSON}${SSJSON}}"
  KEY="relay://$(printf '%s' "$RAW" | base64 -w0 | tr '+/' '-_' | tr -d '=')"
fi
printf '%s\n' "$KEY" > /etc/relay/key
chmod 600 /etc/relay/key

say "result"
for unit in gost sing-box; do printf '  %-9s %s
' "$unit" "$(systemctl is-active "$unit")"; done
ss -lnt | grep -E "127.0.0.1:(1000[0-4]|101[0-3][0-9]|18080)|:(${TLSPORT}|${SSPORT}|${SSXPORT}) " | awk '{print "  listening " $4}'
cat <<SUMMARY

  Done. The app that gave you this command already has what it needs:
  press Test in its tunnel settings.

    server          ${DOMAIN}
    tunnel password ${PW}
    api password    ${APW}
SUMMARY
if [ -n "$PIN" ]; then
  echo "    certificate     sha256 ${PIN}"
  echo "                    (the apps pin this the first time they connect)"
fi
AT="${DOMAIN}"
if [ "$TLSPORT" != 443 ]; then AT="${DOMAIN}:${TLSPORT}"; fi
cat <<SUMMARY

    single-IP  wss://${AT}/gw     (exits here)
    multi-IP   wss://${AT}/ex     (exits at the location the app picks)
    control    https://${AT}/api/config/chains/exit-chain
SUMMARY
if [ "$WITH_SS" = 1 ]; then
  cat <<SUMMARY
    shadowsocks ${DOMAIN}:${SSPORT}  (exits here)
                ${DOMAIN}:${SSXPORT}  (exits at the location the app picks)
                ${SSMETHOD}
SUMMARY
fi
if [ "$SLOTS" -gt 0 ]; then
  cat <<SUMMARY
    other devices  ${SLOTS} locations, VLESS on /vl and VMess on /vm
                   (the app picks them: Settings, Share with other devices -
                   running this again resets them, so share again after)
SUMMARY
fi
cat <<SUMMARY

  For phones - ovpn-mobile.py --tunnel wants:  server ${DOMAIN}  path /vl  uuid ${UUID}

  The key for either app - Settings, Your own tunnel, Paste a key.
  It holds the passwords above: treat it like one. Shown again by
  cat /etc/relay/key

${KEY}

SUMMARY
if [ "$VERIFY_EXIT" = 0 ]; then
  # Only the placeholder chains above took --no-verify-exit; the app sends
  # its own exit chain when it connects, checked unless told otherwise.
  cat <<SUMMARY
  --no-verify-exit: the exit chains written here do not check the provider's
  certificate, but the app replaces them with its own every time it
  connects. Turn exit checking off in the app for this server ("check the
  exit") only if connecting through the exit fails entirely: checking is
  what keeps upload fast from a server in Iran, and without it uploads
  through the exit are likely to drop to about 10 KB/s.

SUMMARY
fi
