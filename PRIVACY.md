# Privacy policy

Relay has no analytics, advertising, crash-reporting service, or background
telemetry, on Windows or on Android. Nothing is sent to the Relay project or
its developers: there is no Relay server, and no account, usage or diagnostic
data is collected or retained by the project. The app transfers information
to networked systems only when the user requests or enables a feature that
needs that transfer, and only to the services listed below.

## What is sent where

When the user connects, Relay sends network traffic and the chosen provider's
service credentials to that provider's proxy or VPN endpoints. If the user
configures their own tunnel server, traffic and tunnel credentials are also
sent to that server and, when it is behind one, its CDN.

Besides the provider and the user's own server, these third-party services
can be contacted, each only by the feature named:

- **Cloudflare** - DNS-over-HTTPS (`cloudflare-dns.com`, and `1.1.1.1` in
  Tunnel mode) to look up provider and server addresses without the local
  resolver; its trace page (`www.cloudflare.com/cdn-cgi/trace`) to see which
  address and location a connection leaves from and whether Cloudflare serves
  it; and its speed test (`speed.cloudflare.com`) to measure a connection.
- **Google Public DNS** (`dns.google`) - DNS-over-HTTPS, as the alternative
  to Cloudflare's.
- **Surfshark** (`api.surfshark.com`, `my.surfshark.com`) and **Windscribe**
  (`api.windscribe.com`, `assets.windscribe.com`) - only for an account the
  user adds with that provider: signing in, and fetching its server list and
  service credentials.
- **ipify** (`api.ipify.org`) - asked through a connection to see the
  address that connection leaves from.
- **ipwho.is** - the address-owner lookup, which names the hosting company
  behind a server address. It is sent the exit addresses being looked up
  (over HTTPS, one address per request); answers are cached on the device so
  an address is not asked about twice. The Windows scripts skip it with
  `-NoOwner` and the Linux scripts with `--no-owner`, and the Windows app has
  a setting to turn it off; with it off no address is sent and only cached
  answers are shown.

Site tests contact the sites the user names, through the connection being
tested.

The server installer (`tunnel/install-server.sh`), run on the user's own
server, downloads gost and sing-box from their GitHub release pages, installs
packages from the server's own package mirrors, and, when given a domain,
asks Let's Encrypt for a certificate for it.

## Windows

Settings, logs, state, account records, server lists and generated
configurations are kept in the user's own data folder,
`%LOCALAPPDATA%\Relay` (for example `C:\Users\<name>\AppData\Local\Relay`),
not in the folder the app was extracted to. On first start, files an older
portable copy had kept beside the exe are copied there. Run from the source
repository instead, the same files are kept in the repository folder.

Account passwords are sealed with the current Windows user's own key (DPAPI)
and are readable only by that user on that machine. Provider proxy
credentials are also written to credential files in the data folder, with
Windows access controls restricted to that user. Some generated mobile or
inline-auth configurations can contain credentials in clear text; the app
labels this behavior and does not publish those files.

Relay changes the Windows system proxy only when the user enables routing all
of Windows. It saves the previous configuration before making the change and
restores it on disconnect, exit, failed connection, or recovery after an
unclean shutdown.

When the user chooses Tunnel instead of the proxy setting, Relay asks Windows
for administrator rights and runs sing-box to add a temporary network adapter
and its routes, so that every program's traffic reaches the same connection.
DNS lookups are then sent over HTTPS to Cloudflare's 1.1.1.1 through that
connection. The adapter, its routes and its firewall filters are removed on
disconnect, exit, failed connection, or when the connection's proxy process
stops.

To erase Relay's data on Windows: quit it from the tray, then delete the
`%LOCALAPPDATA%\Relay` folder and the folder the app was extracted to.

## Android

The Android app routes traffic through Android's VpnService: Android asks
the user's permission before the first connection and shows its VPN key icon
while one is up. The VPN interface, its routes and its DNS exist only while
connected.

On the device it keeps:

- server configurations and the cached lists it builds from them, in the
  app's own folder on shared storage (`Android/data/com.erelay.relay/files`),
  which a file manager or a USB cable can read. No credential is kept there.
- settings in the app's private preferences.
- account passwords, sessions and provider service credentials, and the
  passwords of the user's own tunnel and of Wi-Fi sharing, in an encrypted
  store whose key is held by the Android Keystore. Where that store cannot be
  opened, an account password is not kept and has to be entered again, and
  the tunnel and Wi-Fi sharing passwords are kept in the app's private
  preferences instead.

Android's cloud backup and device-to-device transfer are turned off for the
app, so none of this is copied off the phone by them. Nothing is sent to the
developers.

To erase Relay's data on Android: Settings, Apps, Relay, Storage, Clear
storage - or uninstall the app, which removes all of the above.

## Linux scripts

The command-line scripts keep their settings (`.env`), the OpenVPN
credential file (`.ovpn-auth`), pinned configurations and their state
(`.state`, including the owner-lookup cache `owners.tsv`) in the repository
folder they run from. Deleting that folder erases them. A systemd service
installed with `--install-service` also leaves
`/etc/systemd/system/ovpn-pin.service` and `/usr/local/lib/ovpn-pin`.

## The user's own server

Sharing configs with other devices is done only when the user asks for it.
For each location chosen, Relay sends the user's own server the address of a
provider exit and that provider's account credentials, through the server's
API, and asks the server to save them to its configuration file so the
location keeps working after a restart. The credentials stay on the user's
server until the location is pointed elsewhere or the installer is run
again. The links Relay then shows give whoever holds them use of that
server.

## Split tunnelling

Split tunnelling is off unless the user turns it on. When it is on, the
connections it lists (or, with "Only these", the ones it does not list) leave
by the user's own internet line instead of the connection, and are visible to
that line like any traffic without a VPN. With "Except these", the names it
sends that way are also looked up from the line's own DNS server. The Iran
lists it can use are files shipped inside the app; nothing is downloaded to
use them.

## Erasing everything

There is nothing to erase anywhere else: the Relay project keeps no copy of
anything. Data sent to a provider, to the user's own server, or to the
services listed above is subject to that party's own policy.
