"""The whole machine through the connection, not just the programs that ask.

The proxy setting is a request. Browsers honour it, and so does most of what
Windows ships, but a game, a command-line tool or a messenger with network
code of its own goes straight past it. This is the other way to route
Windows: a virtual network adapter that every program's traffic falls into
whether it knows about proxies or not, handed to the same worker on the same
port. Everything from the worker on is unchanged - the exit, the meter, the
host list.

Mirage does not drive an adapter itself. It runs sing-box, which creates one
with wintun (carried inside sing-box.exe, there is no driver to install),
points the routes at it, keeps Windows from asking the other adapters' DNS
servers, and turns each TCP connection it catches into a SOCKS5 request to
127.0.0.1:<port>.

Three things the worker cannot carry, and what happens to them instead:

  UDP   The provider's proxy is HTTP CONNECT, which is TCP and nothing else.
        UDP is refused on the spot rather than dropped, so a browser gives up
        on QUIC at once and falls back to TCP instead of waiting it out. Calls
        and games that need UDP do not work while this is on.
  DNS   Answered by sing-box, which asks 1.1.1.1 over HTTPS through the exit.
        The forged answers on this line never get a say.
  IPv6  Given no address, so it is unreachable rather than leaking around
        the adapter, and answers come back IPv4 only so nothing tries it.

What must not fall into the adapter is what carries it: the worker's own
connection to the exit, left out by address, and gost's to the server when
the way out is a tunnel of your own, left out by process. This app is left
out too, so a reachability test still measures this line and not the exit's.
Running from source that means every python.exe, which is the price of the
worker being an ordinary interpreter there.

Split tunnelling (split.py) is decided here too when the adapter is up:
sing-box sees each connection's program, address and - by sniffing its
first packet - its name, which is more than the worker is ever told. What
goes around the exit leaves by the direct outbound, UDP included, and a
listed name is looked up from this line's own resolver rather than through
the exit, so it is answered as seen from here.

Creating an adapter takes administrator rights, so sing-box is started by a
small elevated PowerShell script: one UAC prompt per connect, none if Mirage
is already elevated. An unelevated window cannot stop an elevated process,
so it asks - the same way the sweep is asked. It drops a stop file, and the
script ends sing-box and says so in a done file. The script also watches the
worker. With the kill switch off, if that goes it takes the adapter down with
it, rather than leave every program on the machine pointed at a port nobody
answers. With it on (the default) leaving them pointed there is the point:
the adapter stays and traffic fails until the window reconnects or is told
to disconnect - see WRAPPER.
"""

import ctypes
import hashlib
import ipaddress
import json
import os
import re
import subprocess
import sys
import time

import paths
from sweep import CREATE_NO_WINDOW, _ps_array, _ps_string, discard, elevated

# The adapter's name in Network Connections, and what the script waits for.
ADAPTER = 'MirageTun'
# Not one of the private ranges below: those are routed around the adapter,
# and the adapter's own subnet must not be one of the things routed around.
# sing-box answers DNS on the next address, 198.18.0.2.
ADDRESS = '198.18.0.1/30'
DOH = '1.1.1.1'

# Never into the adapter. The LAN, link-local, multicast and broadcast stay
# where they are - a printer or a router's page reached through an exit in
# another country is not reached at all.
LOCAL_NETS = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16',
              '169.254.0.0/16', '224.0.0.0/4', '255.255.255.255/32')

# How long the adapter gets to appear once sing-box is running. The UAC
# prompt is not counted: that waits for a person, for as long as it takes.
STARTUP_SECONDS = 25
STOP_SECONDS = 8

KINDS = ('proxy', 'tun')


def available():
    return paths.singbox_exe() is not None


def is_admin():
    if os.name != 'nt':
        return False
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def _path_rx(path):
    """A process path as sing-box matches it, ignoring case.

    sing-box reports a Win32 path, and Windows does not care whether it is
    C:\\ or c:\\ - so neither may the rule. Go's regexp takes an escaped
    punctuation character as itself, which is all re.escape produces.
    """
    return '(?i)^' + re.escape(os.path.normpath(path)) + '$'


def line_dns():
    """This line's own resolver: the first IPv4 DNS server Windows has, or
    None. Asked before the adapter is up - once it is, the adapter's own
    address is among them, which is the one answer that is no use here.
    """
    if os.name != 'nt':
        return None
    try:
        from ctypes import wintypes

        class IP_ADDR_STRING(ctypes.Structure):
            pass
        IP_ADDR_STRING._fields_ = [
            ('Next', ctypes.POINTER(IP_ADDR_STRING)),
            ('IpAddress', ctypes.c_char * 16),
            ('IpMask', ctypes.c_char * 16),
            ('Context', wintypes.DWORD)]

        class FIXED_INFO(ctypes.Structure):
            _fields_ = [
                ('HostName', ctypes.c_char * 132),
                ('DomainName', ctypes.c_char * 132),
                ('CurrentDnsServer', ctypes.POINTER(IP_ADDR_STRING)),
                ('DnsServerList', IP_ADDR_STRING),
                ('NodeType', wintypes.UINT),
                ('ScopeId', ctypes.c_char * 260),
                ('EnableRouting', wintypes.UINT),
                ('EnableProxy', wintypes.UINT),
                ('EnableDns', wintypes.UINT)]

        size = wintypes.ULONG(0)
        get = ctypes.windll.iphlpapi.GetNetworkParams
        get(None, ctypes.byref(size))
        buf = ctypes.create_string_buffer(size.value or 1024)
        if get(buf, ctypes.byref(size)) != 0:
            return None
        info = ctypes.cast(buf, ctypes.POINTER(FIXED_INFO)).contents
        node = info.DnsServerList
        adapter_net = ipaddress.ip_network('198.18.0.0/15')
        while True:
            text = node.IpAddress.split(b'\0', 1)[0].decode('ascii', 'replace')
            try:
                addr = ipaddress.IPv4Address(text.strip())
            except ValueError:
                addr = None
            if (addr is not None and not addr.is_loopback
                    and not addr.is_unspecified and addr not in adapter_net):
                return str(addr)
            if not node.Next:
                return None
            node = node.Next.contents
    except Exception:
        return None


# Kept in step with split.DNS_PROXIES; tun does not import split.
DNS_PROXIES = frozenset((
    '178.22.122.100', '185.51.200.2', '185.51.200.1',
    '10.202.10.202', '10.202.10.102', '10.202.10.10', '10.202.10.11',
    '185.55.226.26', '185.55.225.25', '185.55.225.26',
    '78.157.42.100', '78.157.42.101',
))


def line_answers(resolver, timeout=1.5):
    """Whether this resolver answers at all, by one plain query. sing-box
    gives a server that never answers ten seconds per name before it moves
    on, so a silent line resolver is better not asked."""
    import random
    import socket
    import struct
    if not resolver:
        return False
    qid = random.randrange(1 << 16)
    name = b''.join(bytes([len(p)]) + p for p in (b'www', b'aparat', b'com'))
    query = struct.pack('>HHHHHH', qid, 0x0100, 1, 0, 0, 0) + name + b'\0' \
        + struct.pack('>HH', 1, 1)
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(timeout)
            s.sendto(query, (str(resolver), 53))
            data = s.recv(512)
        return len(data) >= 12 and struct.unpack('>H', data[:2])[0] == qid
    except OSError:
        return False


def line_dns_checked():
    """line_dns(), when it answers; None otherwise."""
    resolver = line_dns()
    return resolver if resolver and line_answers(resolver) else None


def smart_dns(r, line_resolver):
    """With the Iran switch on: every other name is asked of this line's
    resolver first, and that answer is kept only when it is an Iranian
    address - sing-box's address filter: an answer outside `ip_cidr` skips
    the rule, and the name goes on to the exit's resolver. Kept, it brings
    the connection to an Iranian address, which the route sends straight.
    Iran's filtering address (10.10.34.x) is not in the ranges, so a
    filtered name always falls through to the exit."""
    if not (r and r.get('smart') and r.get('iranNets') and line_resolver):
        return []
    if line_resolver in DNS_PROXIES:
        return []
    return [{'ip_cidr': list(r['iranNets']), 'action': 'route',
             'server': 'dns-line'}]


def _split_rules(r, line_resolver):
    """Route and DNS rules for split tunnelling, from split.rules().

    Programs and destinations are separate rules because sing-box ANDs the
    two kinds of condition within one rule, and these are meant as either.
    In "only", a listed UDP flow is refused rather than sent through: the
    exit is TCP and nothing else, and refusing at once is what makes QUIC
    fall back to TCP straight away.
    """
    route, dns = [], []
    apps = list(r.get('apps') or ())
    dest = {}
    if r.get('names'):
        dest['domain_suffix'] = list(r['names'])
    if r.get('nets'):
        dest['ip_cidr'] = list(r['nets'])

    if r['mode'] == 'bypass':
        if apps:
            route.append({'process_name': apps, 'action': 'route',
                          'outbound': 'direct'})
        if dest:
            route.append(dict(dest, action='route', outbound='direct'))
        if r.get('names') and line_resolver:
            dns.append({'domain_suffix': list(r['names']), 'action': 'route',
                        'server': 'dns-line'})
        dns.extend(smart_dns(r, line_resolver))
    else:
        conds = ([{'process_name': apps}] if apps else []) + ([dest] if dest else [])
        for cond in conds:
            route.append(dict(cond, network='udp', action='reject'))
            route.append(dict(cond, action='route', outbound='proxy'))
    return route, dns


def build_config(port, keep=(), own=(), carriers=(), log_path=None,
                 split_rules=None, line_resolver=None):
    """The whole sing-box configuration, as a dict.

    A plain function of its arguments so the test can read what would be
    run without running it.

    keep      addresses that must reach the internet around the adapter -
              the exit the worker is connected to. Anything that is not a
              public IPv4 address is skipped: a tunnel's local end is
              127.0.0.1:9091 and has no business in a route table.
    own       programs whose connections go straight out: this app.
    carriers  programs that carry the tunnel itself: gost. Their DNS is
              asked directly too - asked through the exit, it would wait on
              the very connection it was trying to bring back.
    split_rules    split.rules(), or None for everything through the exit.
    line_resolver  this line's own DNS server, for names going around.
    """
    kept = []
    for value in keep or ():
        try:
            addr = ipaddress.ip_address(str(value).strip())
        except ValueError:
            continue
        if addr.version == 4 and addr.is_global:
            kept.append(f'{addr}/32')

    straight = [_path_rx(p) for p in list(own or ()) + list(carriers or ())
                if p]
    carried = [_path_rx(p) for p in carriers or () if p]

    split_route, split_dns = (_split_rules(split_rules, line_resolver)
                              if split_rules else ([], []))
    only = bool(split_rules) and split_rules.get('mode') == 'only'

    rules = []
    if split_rules and split_rules.get('names'):
        # A name is only known if it is read off the connection: the TLS
        # ClientHello, the HTTP Host, QUIC's Initial. Only when names are
        # listed, because sniffing holds every new connection for a moment.
        # Only where a name can be read (TLS, HTTP, QUIC on 80/443): a
        # server that speaks first would otherwise wait out the timeout.
        rules.append({'port': [80, 443], 'action': 'sniff'})
    rules.append({'port': 53, 'action': 'hijack-dns'})
    if straight:
        rules.append({'process_path_regex': straight,
                      'action': 'route', 'outbound': 'direct'})
    rules.extend(split_route)
    if not only:
        rules.append({'network': 'udp', 'action': 'reject'})

    dns_rules = []
    if carried:
        dns_rules.append({'process_path_regex': carried,
                          'action': 'route', 'server': 'dns-direct'})
    dns_rules.extend(split_dns)

    dns_servers = [
        {'type': 'https', 'tag': 'dns-remote', 'server': DOH,
         'detour': 'proxy'},
        {'type': 'https', 'tag': 'dns-direct', 'server': DOH},
    ]
    if split_dns:
        dns_servers.append({'type': 'udp', 'tag': 'dns-line',
                            'server': line_resolver})

    log = {'level': 'warn', 'timestamp': True}
    if log_path:
        log['output'] = log_path

    return {
        'log': log,
        'dns': {
            'servers': dns_servers,
            'rules': dns_rules,
            'final': 'dns-remote',
            'strategy': 'ipv4_only',
        },
        'inbounds': [{
            'type': 'tun',
            'tag': 'tun-in',
            'interface_name': ADAPTER,
            'address': [ADDRESS],
            'mtu': 9000,
            'auto_route': True,
            # On Windows this is what stops DNS leaking out of the other
            # adapters, and what makes IPv6 unreachable instead of a way round.
            'strict_route': True,
            'route_exclude_address': list(LOCAL_NETS) + kept,
        }],
        'outbounds': [
            {'type': 'socks', 'tag': 'proxy', 'server': '127.0.0.1',
             'server_port': int(port), 'version': '5', 'network': 'tcp'},
            {'type': 'direct', 'tag': 'direct'},
        ],
        'route': {
            'rules': rules,
            # In "only", what is not listed goes straight out - UDP and all.
            'final': 'direct' if only else 'proxy',
            # Without it, sing-box's own connections - the direct ones, and
            # its DNS - would be routed into its own adapter.
            'auto_detect_interface': True,
            'default_domain_resolver': 'dns-direct',
        },
    }


# The kill switch's last line, for when the adapter itself is gone.
#
# While sing-box runs, holding traffic is the adapter's job: every program's
# packets fall into it and go nowhere. When sing-box is gone - it crashed, an
# antivirus took it - there is no adapter to fall into, and the routes go
# with it, so every program is back on the bare line. Then the only thing
# left that can stop them is Windows Firewall:
#
#   Outbound is blocked by default on every profile, and allowed only for
#   the programs that carry a reconnect (this app's exe or interpreter,
#   sing-box, gost) and for the local network - the same addresses the
#   adapter routes around. DNS is blocked even there: a lookup sent to the
#   router is a list of the sites being opened, handed to the line.
#
#   What the profiles said before is kept under HKLM, which only an
#   administrator can write, and put back exactly when the block is lifted:
#   by the next adapter once it is up, by Disconnect or quitting, and when
#   Mirage opens again. It survives a restart of Windows on purpose - that is
#   what "until Mirage opens again" means - and README says how to lift it by
#   hand if Mirage is gone for good.
#
#   The app used to be called Relay, and so were the key and the rules. A
#   hold the old version left behind is still this app's to lift: a block
#   takes over its saved profiles (they are what was there before any hold),
#   and lifting restores from whichever key there is and removes both, and
#   both groups of rules.
#
# Plain text, not a str.format template: it is put in front of WRAPPER, and
# run on its own by Tun.release_firewall.
KILL_GROUP = 'Mirage kill switch'
KILL_KEY = r'SOFTWARE\Mirage\KillSwitch'
LEGACY_KILL_GROUP = 'Relay kill switch'
LEGACY_KILL_KEY = r'SOFTWARE\Relay\KillSwitch'
KILL_PS = r'''
$MirageKillGroup = 'Mirage kill switch'
$MirageKillKey = 'HKLM:\SOFTWARE\Mirage\KillSwitch'
$LegacyKillGroup = 'Relay kill switch'
$LegacyKillKey = 'HKLM:\SOFTWARE\Relay\KillSwitch'

function Remove-LegacyKillKey {
    Remove-Item -LiteralPath $LegacyKillKey -Recurse -Force -ErrorAction SilentlyContinue
    # Its parent, HKLM\SOFTWARE\Relay, was made for it alone.
    $parent = Split-Path -Parent $LegacyKillKey
    $left = Get-Item -LiteralPath $parent -ErrorAction SilentlyContinue
    if ($left -and $left.SubKeyCount -eq 0 -and $left.ValueCount -eq 0) {
        Remove-Item -LiteralPath $parent -Force -ErrorAction SilentlyContinue
    }
}

function Block-MirageOutbound {
    param([string[]] $Programs)
    try {
        $profiles = @(Get-NetFirewallProfile -PolicyStore PersistentStore -ErrorAction Stop)
    } catch {
        return "Windows Firewall could not be read ($($_.Exception.Message)), so nothing is holding traffic"
    }
    try {
        # Only the first block saves what the profiles said: a second one
        # would save the block itself as the thing to put back. A hold the
        # old Relay left counts as a first block already made: what it saved
        # is what was there before it, so that is what is carried over.
        if (-not (Test-Path -LiteralPath $MirageKillKey)) {
            New-Item -Path $MirageKillKey -Force -ErrorAction Stop | Out-Null
            $legacy = Get-ItemProperty -LiteralPath $LegacyKillKey -ErrorAction SilentlyContinue
            foreach ($p in $profiles) {
                $name = [string]$p.Name
                $was = if ($legacy) { [string]$legacy.$name } else { [string]$p.DefaultOutboundAction }
                New-ItemProperty -LiteralPath $MirageKillKey -Name $name -Value $was -PropertyType String -Force -ErrorAction Stop | Out-Null
            }
        }
        Remove-LegacyKillKey
        Remove-NetFirewallRule -Group $LegacyKillGroup -ErrorAction SilentlyContinue
        Remove-NetFirewallRule -Group $MirageKillGroup -ErrorAction SilentlyContinue
        foreach ($prog in $Programs) {
            if ($prog -and (Test-Path -LiteralPath $prog -PathType Leaf)) {
                New-NetFirewallRule -DisplayName ("$MirageKillGroup - " + [IO.Path]::GetFileName($prog)) -Group $MirageKillGroup -Direction Outbound -Action Allow -Program $prog -ErrorAction Stop | Out-Null
            }
        }
        New-NetFirewallRule -DisplayName "$MirageKillGroup - local network" -Group $MirageKillGroup -Direction Outbound -Action Allow -RemoteAddress @('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '169.254.0.0/16', '224.0.0.0/4', '255.255.255.255', 'fe80::/10', 'ff00::/8') -ErrorAction Stop | Out-Null
        New-NetFirewallRule -DisplayName "$MirageKillGroup - no DNS (UDP)" -Group $MirageKillGroup -Direction Outbound -Action Block -Protocol UDP -RemotePort 53 -ErrorAction Stop | Out-Null
        New-NetFirewallRule -DisplayName "$MirageKillGroup - no DNS (TCP)" -Group $MirageKillGroup -Direction Outbound -Action Block -Protocol TCP -RemotePort 53 -ErrorAction Stop | Out-Null
        Set-NetFirewallProfile -Profile Domain, Private, Public -DefaultOutboundAction Block -ErrorAction Stop
    } catch {
        $why = $_.Exception.Message
        # Half a block is no block, and the key is what Mirage reads as "held"
        # (tun.firewall_held): left behind, the window would say traffic is
        # held while it goes out on the bare line. So everything this did is
        # undone - profiles put back, rules and key removed - and the failure
        # is said as what it is.
        Unblock-MirageOutbound
        return "Windows Firewall would not hold traffic ($why), so nothing is holding it"
    }
    $off = @($profiles | Where-Object { -not $_.Enabled } | ForEach-Object { [string]$_.Name })
    if ($off.Count) {
        return ('Windows Firewall is turned off for the ' + ($off -join ', ') + ' profile, so traffic is not held there')
    }
    return $null
}

function Unblock-MirageOutbound {
    # This app's key first; the old Relay's when that is all there is.
    $key = @($MirageKillKey, $LegacyKillKey) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($key) {
        $saved = Get-ItemProperty -LiteralPath $key -ErrorAction SilentlyContinue
        foreach ($name in 'Domain', 'Private', 'Public') {
            $was = if ($saved) { [string]$saved.$name } else { '' }
            if ($was -notin 'Allow', 'Block', 'NotConfigured') { $was = 'NotConfigured' }
            Set-NetFirewallProfile -Profile $name -DefaultOutboundAction $was -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $MirageKillKey -Recurse -Force -ErrorAction SilentlyContinue
        Remove-LegacyKillKey
    }
    Remove-NetFirewallRule -Group $MirageKillGroup -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -Group $LegacyKillGroup -ErrorAction SilentlyContinue
}
'''

# Run elevated. Doubled braces are literal; single ones are filled in by
# str.format. Everything it says, it says in a file, because elevation
# leaves no pipe to say it down.
#
# Two things it does that are about trust rather than about sing-box:
#
#   The configuration is inside the script, not beside it. The elevated side
#   writes it, as raw UTF-8, to a file of its own in Windows' temp folder
#   that only SYSTEM and Administrators can change, and points sing-box at
#   that - so there is no config.json in a folder this user can write for
#   something else to swap between the UAC prompt and sing-box reading it
#   (an elevated sing-box with somebody else's configuration writes its log
#   wherever it is told). The script itself is checked by hash before it
#   runs (sweep.elevated).
#
#   The kill switch. With a `hold` file present, the worker it carries
#   traffic to going away no longer takes the adapter down: every program
#   keeps going into the adapter and from there to a port nobody answers, so
#   nothing reaches the internet on the bare line until the window says
#   otherwise - a stop file (Disconnect, quitting) or a new worker's pid in
#   `watch`, which it then watches instead. It says so in `held`. The window
#   going away does not end the hold either: a crashed window is no reason to
#   put every program back on the bare line, and Mirage opening again is what
#   lets it go (Tun.tidy). And if sing-box itself goes, which takes the
#   adapter with it, the firewall takes over the hold (KILL_PS) until a new
#   adapter is up or Mirage is told to let go.
WRAPPER = r'''
$ErrorActionPreference = 'Continue'
$exe    = {exe}
$out    = {out}
$err    = {err}
$pidf   = {pidf}
$ready  = {ready}
$failed = {failed}
$stop   = {stop}
$done   = {done}
$nic    = {nic}
$watch  = {watch}
$ui     = {ui}
$holdf  = {holdf}
$held   = {held}
$watchf = {watchf}
$allow  = {allow}
$config = {config}

Remove-Item -LiteralPath $done -Force -ErrorAction SilentlyContinue

# A sing-box left from an earlier run still holding the adapter: the window
# that ran it was ended by force (an update, Task Manager), its script went
# with it, and nothing recorded is left to stop. sing-box would then refuse
# with "Cannot create a file when that file already exists". Ours are told
# apart by the configuration file this script names (mirage-sing-box-*), so
# any other sing-box - the Configs section's own in proxy mode, somebody
# else's - is left alone. Then a moment for Windows to take the adapter
# away, which it does after the process is gone, not with it.
$killedOld = 0
try {{
    Get-CimInstance Win32_Process -Filter "Name='sing-box.exe'" -ErrorAction Stop |
        Where-Object {{ $_.CommandLine -like '*mirage-sing-box-*' }} |
        ForEach-Object {{
            Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
            $killedOld++
        }}
}} catch {{ }}
if ($killedOld -gt 0) {{
    # Its log is the one this run writes too, and what it said last is not
    # why this one fails, if it does.
    Start-Sleep -Milliseconds 300
    Remove-Item -LiteralPath (Join-Path (Split-Path -Parent $pidf) 'sing-box.log') -Force -ErrorAction SilentlyContinue
}}
if (Get-Command Get-NetAdapter -ErrorAction SilentlyContinue) {{
    $until = (Get-Date).AddSeconds(8)
    while ((Get-NetAdapter -Name $nic -IncludeHidden -ErrorAction SilentlyContinue) -and ((Get-Date) -lt $until)) {{
        Start-Sleep -Milliseconds 250
    }}
}}

$cfg = $null
try {{
    # The configuration goes to a file only SYSTEM and Administrators can
    # touch, created here (CreateNew, so a file somebody planted under the
    # same random name makes this fail rather than be used) in Windows' own
    # temp folder, where an ordinary user can add files but not change or
    # delete anyone else's. Raw UTF-8 bytes, no byte-order mark, which
    # sing-box's JSON reader would refuse.
    $cfg = Join-Path (Join-Path $env:SystemRoot 'Temp') ('mirage-sing-box-' + [guid]::NewGuid().ToString('N') + '.json')
    $sec = New-Object System.Security.AccessControl.FileSecurity
    $sec.SetAccessRuleProtection($true, $false)
    foreach ($sid in 'S-1-5-18', 'S-1-5-32-544') {{
        $who = New-Object System.Security.Principal.SecurityIdentifier $sid
        $sec.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule $who, 'FullControl', 'Allow'))
    }}
    $fs = New-Object System.IO.FileStream $cfg, ([IO.FileMode]::CreateNew), ([System.Security.AccessControl.FileSystemRights]::FullControl), ([IO.FileShare]::Read), 4096, ([IO.FileOptions]::None), $sec
    $bytes = (New-Object System.Text.UTF8Encoding $false).GetBytes($config)
    $fs.Write($bytes, 0, $bytes.Length)
    $fs.Close()

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = 'run -c "' + $cfg + '" --disable-color'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $box = [System.Diagnostics.Process]::Start($psi)
    # Read as they come, or a chatty sing-box would fill the pipe and stall.
    # What it logs while it runs goes to sing-box.log as well (the config's
    # log.output), which is what the window reads live.
    $outTask = $box.StandardOutput.ReadToEndAsync()
    $errTask = $box.StandardError.ReadToEndAsync()
    if ($killedOld -gt 0) {{
        # Just after a leftover was ended, Windows can still be taking its
        # adapter away and this one stops at once ("already exists"). It is
        # given one more go here, inside this script, so nobody is asked
        # for approval twice. Only then: a config that is simply wrong
        # fails the same way twice and is said once.
        $t0 = Get-Date
        while (-not $box.HasExited -and ((Get-Date) - $t0).TotalSeconds -lt 3) {{
            Start-Sleep -Milliseconds 200
        }}
        if ($box.HasExited) {{
            Start-Sleep -Seconds 3
            $box = [System.Diagnostics.Process]::Start($psi)
            $outTask = $box.StandardOutput.ReadToEndAsync()
            $errTask = $box.StandardError.ReadToEndAsync()
        }}
    }}
}} catch {{
    if ($cfg) {{ Remove-Item -LiteralPath $cfg -Force -ErrorAction SilentlyContinue }}
    Set-Content -LiteralPath $failed -Value "sing-box would not start: $_" -Encoding utf8
    Set-Content -LiteralPath $done -Value 'failed' -Encoding ascii
    exit 1
}}
Set-Content -LiteralPath $pidf -Value $box.Id -Encoding ascii

$up = $false
$saidGone = $false
$began = Get-Date
$deadline = $began.AddSeconds({startup})
# Where the NetAdapter module is missing, three seconds of sing-box not
# falling over is the nearest thing to an answer there is.
$canAsk = [bool](Get-Command Get-NetAdapter -ErrorAction SilentlyContinue)
while (-not $box.HasExited) {{
    if (Test-Path -LiteralPath $stop) {{ break }}
    if (Test-Path -LiteralPath $watchf) {{
        # A new worker on the same port: carry on, watching it instead.
        $next = 0
        try {{ $next = [int]((Get-Content -LiteralPath $watchf -Raw).Trim()) }} catch {{ $next = 0 }}
        Remove-Item -LiteralPath $watchf -Force -ErrorAction SilentlyContinue
        if ($next -gt 0) {{
            $watch = $next
            Remove-Item -LiteralPath $held -Force -ErrorAction SilentlyContinue
        }}
    }}
    # The window itself. With the kill switch on, a window that has gone (a
    # crash, a kill) does not end the hold: the adapter stays, and Mirage
    # opening again is what lets it go. Only said, so the held note is true.
    $uiGone = ($ui -and -not (Get-Process -Id $ui -ErrorAction SilentlyContinue))
    if ($watch -and -not (Get-Process -Id $watch -ErrorAction SilentlyContinue)) {{
        if (Test-Path -LiteralPath $holdf) {{
            Set-Content -LiteralPath $held -Value 'the proxy it was carrying traffic to has stopped; traffic is held' -Encoding utf8
            $watch = 0
        }} else {{
            Set-Content -LiteralPath $failed -Value 'the proxy it was carrying traffic to has stopped' -Encoding utf8
            break
        }}
    }}
    if ($watch -eq 0 -and $uiGone -and -not $saidGone) {{
        Set-Content -LiteralPath $held -Value 'Mirage closed while holding traffic; it stays held until Mirage opens again' -Encoding utf8
        $saidGone = $true
    }}
    if (-not $up) {{
        if ($canAsk) {{
            $a = Get-NetAdapter -Name $nic -ErrorAction SilentlyContinue
            $seen = [bool]($a -and $a.Status -eq 'Up')
        }} else {{
            $seen = ((Get-Date) - $began).TotalSeconds -ge 3
        }}
        if ($seen) {{
            $up = $true
            # A firewall hold left by the last adapter (see KILL_PS) is let
            # go only now, with this one carrying traffic: lifted before,
            # the seconds in between would be on the bare line.
            Unblock-MirageOutbound
            Set-Content -LiteralPath $ready -Value 'up' -Encoding ascii
        }} elseif ((Get-Date) -gt $deadline) {{
            Set-Content -LiteralPath $failed -Value 'the network adapter did not come up' -Encoding utf8
            break
        }}
        Start-Sleep -Milliseconds 300
    }} else {{
        Start-Sleep -Milliseconds 1000
    }}
}}

if ($box.HasExited) {{
    if (-not (Test-Path -LiteralPath $failed) -and -not (Test-Path -LiteralPath $stop)) {{
        $why = "sing-box stopped by itself (exit " + $box.ExitCode + ")"
        # The adapter went with it, so every program is on the bare line
        # again - unless the kill switch is on, and the firewall holds them.
        if ($up -and (Test-Path -LiteralPath $holdf)) {{
            $fw = Block-MirageOutbound $allow
            if ($fw) {{
                $why += "; $fw"
            }} else {{
                $why += '; traffic is held by Windows Firewall until Mirage reconnects or is told to disconnect'
            }}
        }}
        Set-Content -LiteralPath $failed -Value $why -Encoding utf8
    }}
}} else {{
    # The adapter, its routes and its firewall filters all belong to the
    # process, and go when it does.
    Stop-Process -Id $box.Id -Force -ErrorAction SilentlyContinue
    $box.WaitForExit(5000) | Out-Null
}}
try {{
    if ($outTask.Wait(2000)) {{ Set-Content -LiteralPath $out -Value $outTask.Result -Encoding utf8 }}
    if ($errTask.Wait(2000)) {{ Set-Content -LiteralPath $err -Value $errTask.Result -Encoding utf8 }}
}} catch {{ }}
Remove-Item -LiteralPath $cfg -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pidf -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $held -Force -ErrorAction SilentlyContinue
Set-Content -LiteralPath $done -Value 'done' -Encoding ascii
'''


def _ps_list(values):
    """A PowerShell array literal of single-quoted strings."""
    return '@(' + ','.join(_ps_string(v) for v in values) + ')'


def carriers_of_a_reconnect():
    """The programs a firewall hold still lets out (KILL_PS): whatever this
    app runs as - the frozen exe, or the interpreter running it from source,
    both window and worker - and sing-box and gost, without which a
    reconnect has no way to come up."""
    out = []
    exe = os.path.normpath(sys.executable) if sys.executable else ''
    if exe:
        out.append(exe)
        # From source the window may be pythonw.exe and the worker python.exe.
        folder, name = os.path.split(exe)
        for other in ('python.exe', 'pythonw.exe'):
            if name.lower() in ('python.exe', 'pythonw.exe') \
                    and name.lower() != other:
                out.append(os.path.join(folder, other))
    for found in (paths.singbox_exe(), paths.gost_exe(), paths.xray_exe()):
        if found:
            out.append(os.path.normpath(found))
    return out


def firewall_held():
    """Whether a firewall hold from KILL_PS is in place. Read from the key
    only an administrator can write, so this needs no elevation to ask. The
    old Relay's key counts too: a hold it left is still a hold, and this app
    is what lifts it."""
    if os.name != 'nt':
        return False
    import winreg
    for path in (KILL_KEY, LEGACY_KILL_KEY):
        try:
            key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path, 0,
                                 winreg.KEY_READ | winreg.KEY_WOW64_64KEY)
            winreg.CloseKey(key)
            return True
        except OSError:
            continue
    return False


def explain(error):
    """One sentence for a failure this module raised, or None."""
    text = str(error)
    kind, _, detail = text.partition(':')
    detail = detail.strip()
    if kind == 'tun-missing':
        return ('sing-box.exe is not beside Mirage, so there is no adapter to '
                'bring up. Use the proxy setting instead.')
    if kind == 'tun-refused':
        return ('Windows was not given administrator approval, so the adapter '
                'was not brought up.')
    if kind == 'tun-failed':
        return ('The network adapter did not come up'
                + (f': {detail[:200]}' if detail else '.'))
    return None


class Tun:
    """One adapter at a time, and always able to say whether it is up."""

    def __init__(self, alive):
        # The worker's own liveness check, passed in rather than imported:
        # it is the one that knows a dead process from a held handle.
        self.alive = alive
        self.dir = os.path.join(paths.STATE_DIR, 'tun')

    def _path(self, name):
        return os.path.join(self.dir, name)

    def _remove(self, *names):
        for name in names:
            try:
                os.remove(self._path(name))
            except OSError:
                pass

    def _read(self, name):
        try:
            with open(self._path(name), encoding='utf-8-sig',
                      errors='replace') as f:
                return f.read().strip()
        except OSError:
            return ''

    def _pid(self):
        try:
            return int(self._read('sing-box.pid') or 0)
        except ValueError:
            return 0

    def record(self):
        """What was started, and for which port - or None."""
        try:
            with open(self._path('state.json'), encoding='utf-8') as f:
                rec = json.load(f)
            return rec if isinstance(rec, dict) else None
        except (OSError, ValueError):
            return None

    def running(self):
        rec = self.record()
        if not rec:
            return None
        if os.path.exists(self._path('done')):
            return None
        return rec if self.alive(self._pid()) else None

    def engaged_for(self, port):
        """Up, and carrying traffic to this port. Another copy's adapter, on
        another port, is somebody's live connection and not ours to touch."""
        rec = self.running()
        return bool(rec and rec.get('port') == port)

    # -- up ------------------------------------------------------------------

    @staticmethod
    def fingerprint(port, own=(), carriers=(), split_rules=None, **_):
        """What decides whether a running adapter is the one a connection
        wants: everything in its configuration except the exit address it
        routes around, which changes with every exit and is covered by the
        process rule anyway (see Engine._engage)."""
        cfg = build_config(port, (), own, carriers, None,
                           split_rules=split_rules, line_resolver=None)
        text = json.dumps(cfg, sort_keys=True)
        return hashlib.sha256(text.encode('utf-8')).hexdigest()

    def same_config(self, port, **want):
        rec = self.record() or {}
        return rec.get('fingerprint') == self.fingerprint(port, **want)

    def rewatch(self, pid):
        """A new worker on the same port: the running script watches it
        from now on, and stops saying traffic is held."""
        try:
            with open(self._path('watch'), 'w', encoding='ascii') as f:
                f.write(str(int(pid or 0)))
        except OSError:
            return False
        rec = self.record() or {}
        rec['watch'] = pid
        try:
            with open(self._path('state.json'), 'w', encoding='utf-8') as f:
                json.dump(rec, f)
        except OSError:
            pass
        return True

    def set_hold(self, on):
        """The kill switch, as the running script reads it: with the `hold`
        file there, the worker going away leaves the adapter up."""
        if on:
            try:
                os.makedirs(self.dir, exist_ok=True)
                with open(self._path('hold'), 'w', encoding='ascii') as f:
                    f.write('hold')
            except OSError:
                return False
        else:
            self._remove('hold')
        return True

    def held(self):
        """Whether the script has said the worker went and it is holding."""
        return os.path.exists(self._path('held'))

    def start(self, port, keep=(), watch=None, own=(), carriers=(),
              split_rules=None, hold=False, config=None):
        """Bring the adapter up, carrying traffic to the worker on `port`.

        Or, given `config`, with that whole sing-box configuration instead:
        the Configs section's, whose own outbounds carry the traffic and
        whose mixed inbound is on `port`. Everything else - the elevation,
        the watch, the kill switch's hold - is the same either way.
        """
        exe = paths.singbox_exe()
        if not exe:
            raise RuntimeError('tun-missing: sing-box.exe was not found')
        self.stop()
        os.makedirs(self.dir, exist_ok=True)
        self._remove('ready', 'failed', 'stop', 'done', 'sing-box.pid',
                     'sing-box.out', 'sing-box.err', 'sing-box.log',
                     'held', 'watch', 'config.json', 'run.ps1')
        self.set_hold(hold)

        # ensure_ascii, so the text is the same bytes whatever PowerShell 5.1
        # makes of the script's encoding - a path with a Persian user name in
        # it comes out the other side unchanged.
        if config is not None:
            built = dict(config)
            built['log'] = dict(built.get('log') or {},
                                output=self._path('sing-box.log'))
            fingerprint = 'configs:' + hashlib.sha256(json.dumps(
                built, sort_keys=True).encode('utf-8')).hexdigest()
        else:
            built = build_config(port, keep, own, carriers,
                                 self._path('sing-box.log'),
                                 split_rules=split_rules,
                                 line_resolver=line_dns_checked()
                                 if split_rules else None)
            fingerprint = self.fingerprint(port, own=own, carriers=carriers,
                                           split_rules=split_rules)
        config = json.dumps(built, ensure_ascii=True)

        wrapper = KILL_PS + WRAPPER.format(
            exe=_ps_string(exe),
            allow=_ps_list(carriers_of_a_reconnect()),
            ui=int(os.getpid()),
            out=_ps_string(self._path('sing-box.out')),
            err=_ps_string(self._path('sing-box.err')),
            pidf=_ps_string(self._path('sing-box.pid')),
            ready=_ps_string(self._path('ready')),
            failed=_ps_string(self._path('failed')),
            stop=_ps_string(self._path('stop')),
            done=_ps_string(self._path('done')),
            nic=_ps_string(ADAPTER),
            watch=int(watch or 0),
            holdf=_ps_string(self._path('hold')),
            held=_ps_string(self._path('held')),
            watchf=_ps_string(self._path('watch')),
            config=_ps_string(config),
            startup=STARTUP_SECONDS)

        with open(self._path('state.json'), 'w', encoding='utf-8') as f:
            json.dump({'port': port, 'watch': watch, 'since': time.time(),
                       'fingerprint': fingerprint}, f)

        # Hash-checked by the elevated side before it runs (sweep.elevated).
        script, script_path = elevated(wrapper, self.dir, 'run')
        if is_admin():
            # Already elevated: no prompt to wait for. Its own process group
            # and no console, so it outlives this window when the setting
            # says the connection should.
            subprocess.Popen(
                ['powershell'] + script, stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=CREATE_NO_WINDOW | 0x200)
        else:
            raise_it = ('Start-Process powershell -Verb RunAs '
                        '-WindowStyle Hidden -ArgumentList ' + _ps_array(script))
            try:
                done = subprocess.run(
                    ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                     '-Command', raise_it],
                    capture_output=True, text=True, timeout=300,
                    creationflags=CREATE_NO_WINDOW)
            except (OSError, subprocess.SubprocessError) as e:
                discard(script_path)
                self._remove('state.json')
                raise RuntimeError(f'tun-failed: could not ask for elevation: {e}')
            if done.returncode != 0:
                # The one that actually happens: the UAC prompt was dismissed.
                discard(script_path)
                self._remove('state.json')
                raise RuntimeError('tun-refused: administrator rights were refused')

        deadline = time.time() + STARTUP_SECONDS + 10
        while time.time() < deadline:
            if os.path.exists(self._path('ready')):
                return self.record()
            if os.path.exists(self._path('done')):
                break
            time.sleep(0.25)
        said = self._why()
        self.stop()
        raise RuntimeError(f'tun-failed: {said or "it did not come up in time"}')

    def _why(self):
        """What went wrong, from whichever of the three files has it."""
        said = self._read('failed')
        for name in ('sing-box.err', 'sing-box.log'):
            tail = self._read(name)
            if tail:
                last = [line for line in tail.splitlines() if line.strip()][-1:]
                if last:
                    said = f'{said} - {last[0]}' if said else last[0]
                    break
        return said[:300]

    # -- down ----------------------------------------------------------------

    def stop(self, wait=STOP_SECONDS):
        """Take the adapter down if it is up, and forget it either way.

        Says whether it was up, which is not the same as whether there were
        files to forget: a script that ended by itself leaves its record
        behind with nothing running."""
        pid = self._pid()
        was_up = bool(pid and self.alive(pid))
        if was_up and not os.path.exists(self._path('done')):
            try:
                with open(self._path('stop'), 'w', encoding='ascii') as f:
                    f.write('stop')
            except OSError:
                pass
            deadline = time.time() + wait
            while time.time() < deadline:
                if os.path.exists(self._path('done')) or not self.alive(pid):
                    break
                time.sleep(0.2)
            if self.alive(pid):
                # The script is gone and sing-box is not. Left like that, the
                # whole machine stays pointed at a worker that may be about to
                # stop - so it is ended directly, which needs the same rights
                # it was started with.
                self._kill(pid)
        self._remove('state.json', 'stop', 'ready', 'sing-box.pid', 'hold',
                     'held', 'watch')
        return was_up

    def _kill(self, pid):
        argv = ['/F', '/PID', str(int(pid))]
        try:
            if is_admin():
                subprocess.run(['taskkill'] + argv, capture_output=True,
                               timeout=15, creationflags=CREATE_NO_WINDOW)
            else:
                subprocess.run(
                    ['powershell', '-NoProfile', '-Command',
                     'Start-Process taskkill -Verb RunAs -WindowStyle Hidden '
                     '-ArgumentList ' + _ps_array(argv)],
                    capture_output=True, timeout=300,
                    creationflags=CREATE_NO_WINDOW)
        except (OSError, subprocess.SubprocessError):
            pass

    def held_by_firewall(self):
        return firewall_held()

    def ended_by_itself(self):
        """The script finished without being asked: a record still here, its
        done file written, and no stop file of ours. stop() writes the stop
        file first and removes the record last, so a stop in progress is
        never mistaken for this."""
        return bool(self.record()
                    and os.path.exists(self._path('done'))
                    and not os.path.exists(self._path('stop')))

    def release_firewall(self):
        """Lift a firewall hold (KILL_PS) and put the profiles back as they
        were. Needs administrator rights, so from an unelevated window it is
        one UAC prompt. True when nothing is held any more - False when the
        prompt was refused, and the machine stays as it was."""
        if not firewall_held():
            return True
        script, script_path = elevated(KILL_PS + '\nUnblock-MirageOutbound\n',
                                       self.dir, 'release')
        try:
            if is_admin():
                subprocess.run(['powershell'] + script, capture_output=True,
                               timeout=120, creationflags=CREATE_NO_WINDOW)
            else:
                # -Wait, unlike the adapter's start: the answer to "is it
                # lifted" is only true once the elevated side has finished.
                subprocess.run(
                    ['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass',
                     '-Command',
                     'Start-Process powershell -Verb RunAs -Wait '
                     '-WindowStyle Hidden -ArgumentList ' + _ps_array(script)],
                    capture_output=True, text=True, timeout=300,
                    creationflags=CREATE_NO_WINDOW)
        except (OSError, subprocess.SubprocessError):
            pass
        discard(script_path)
        return not firewall_held()

    def tidy(self):
        """After a crash: an adapter whose worker is gone is wreckage.

        The script normally sees to this itself. This is for the case where
        the script was killed too, and its files are all that is left."""
        rec = self.record()
        if rec and not self.alive(rec.get('watch') or 0):
            return self.stop()
        return False
