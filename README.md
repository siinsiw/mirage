# Mirage / ovpn-pin

![Mirage network banner](docs/mirage-banner.png)

[English](README.md) · [فارسی](README.fa.md)

## Choose your setup

| Your computer | Start here |
| --- | --- |
| Windows 10/11, 64-bit | [Install the Mirage app](#windows-mirage-app) |
| Android 7 or newer | [Install the Android app](#android) |
| Linux | [Install the `ovpn` command](#linux) |

You need an account with a supported VPN provider. The download does not include an account or password. For the Windows and Android apps, use Surfshark or Windscribe. The Linux steps below use the Surfshark OpenVPN configs included in this repository; you can also put your own provider's `.ovpn` files in `configs/`. The Linux command is OpenVPN only: it has no Windscribe sign-in, own-server tunnel or split tunnelling.

**About Windscribe:** Mirage signs in to Windscribe the way Windscribe's own browser extension does. Windscribe may not allow that, and an account can be blocked for it. Both apps say this once before you sign in.

## Windows: Mirage app

### 1. Download the app

1. Open the [latest GitHub release](https://github.com/siinsiw/ovpn-pin/releases/latest).
2. Under **Assets**, download the file named `Mirage-windows-x64-….zip`. Download the matching `.zip.sha256` file if you want to check the download. Do not choose **Source code**: that is the repository, not the ready-to-run app.
3. In File Explorer, right-click the ZIP and choose **Extract All**. Open the extracted folder that contains `Mirage.exe`. Keep its contents together.

No Git, Python, or installer is needed for this route.

<details>
<summary>Optional: check the ZIP before opening it</summary>

Put the ZIP and its `.sha256` file in the same folder. Open PowerShell in that folder and run:

```powershell
Get-FileHash .\Mirage-windows-x64-*.zip -Algorithm SHA256
Get-Content .\Mirage-windows-x64-*.zip.sha256
```

The two long hashes should match. Windows may show a SmartScreen warning because the app is not code-signed; check the release and checksum before choosing **More info → Run anyway**.

A checksum only shows the file was not damaged; it is published in the same place as the file. To check that a ZIP was really built by this repository's GitHub Actions, even when someone sent it to you on Telegram, use the [GitHub CLI](https://cli.github.com/) (this works once the repository is public):

```powershell
gh attestation verify .\Mirage-windows-x64-*.zip --repo siinsiw/ovpn-pin
```

</details>

### 2. Add your account

1. Double-click `Mirage.exe` in the extracted folder.
2. Click the gear icon, then open **Accounts** and choose **Add an account**.
3. Choose a provider:
   - **Surfshark:** enter the **service username and password** from Surfshark's manual setup page. Your usual login email is not the service username.
   - **Windscribe:** sign in with your Windscribe account and complete the prompt shown by the app.
4. Right after you sign in, Mirage gets your servers ready by itself (the next step). Wait for **Ready**.

### 3. Your servers are pinned for you

A downloaded config usually names a server by hostname, and on a censored line the hostname is what gets a forged answer. Pinning looks up each server's real public IP over DNS-over-HTTPS and writes a separate config with that IP in the `remote` line. Mirage connects using these **pinned copies**.

After you sign in, Mirage does this on its own: it downloads the provider's server list, then pins it. To reach DNS-over-HTTPS it first tries straight out; if that is blocked, it uses a local HTTP proxy that is already running on this computer (v2rayN, Clash, Nekoray, sing-box, Hiddify). If neither works, the setup sheet says so: start your proxy and press **Try again**, or choose the route yourself in **Settings → Servers → Pin them to real addresses** (**Over**: **Straight out**, or **Through a proxy** with its port). Every setup that does not finish is written to `%LOCALAPPDATA%\Mirage\.state\setup.log`.

To refresh the list later, or to pin `.ovpn` files of your own:

1. In **Settings → Accounts**, click **Update servers** on your provider's card. If you have your own `.ovpn` files, keep them together in a folder instead.
2. Open **Settings → Servers → Pin them to real addresses**. Check that **From** points to the folder with the original `.ovpn` files (use **Change…** if not), leave **Into** on the default `pinned` folder, and click **Pin**. The originals stay untouched.
3. The default `pinned` folder joins Mirage's server list automatically. If you chose another **Into** folder, click **Connect through these** when the run finishes.

If **Pin** is disabled, check that **From** contains `.ovpn` files and, when using **Through a proxy**, that the proxy is running on the port shown. If the run writes zero files, read the reason beside the configs before continuing.

### 4. Connect

1. Close Settings and leave the route set to **Provider**.
2. Choose an **Exit location**, or leave **Whichever answers first** selected.
3. Click **Connect**. When connected, the **Seen as** address should appear.
4. Click **Disconnect** when finished.

Mirage keeps account data and settings in `%LOCALAPPDATA%\Mirage`. You can move the extracted app folder later without moving those settings.

**Proxy setting or Tunnel?** In **Settings**, *How Windows is routed* has two answers. **Proxy setting** reaches browsers and most apps, but a program that ignores the Windows proxy (many games, command-line tools, some messengers) goes around it, and so does all UDP, including a browser's WebRTC, which can show a website your real address. Choose **Tunnel (TUN)** when you need everything on the machine to go through the connection.

**Kill switch.** *Block internet if the connection drops* is on by default. If the connection drops, nothing goes out on your normal line until you reconnect or press **Disconnect**. In Tunnel mode, if the tunnel program itself stops (a crash, or an antivirus removing `sing-box.exe`), Windows Firewall takes over and blocks the internet for everything except Mirage, until Mirage reconnects, you press **Disconnect**, or Mirage is opened again. Lifting that block needs administrator approval (one UAC prompt).

### Check for a newer version

Open **Settings → About this copy** and click **Check**. Mirage asks GitHub for its list of releases and says whether a newer Windows version is out; nothing about your computer is sent. It only checks when you click.

### If it does not connect

- Check the account in **Settings → Accounts**. Surfshark needs its service credentials.
- If the server list is empty, press **Update servers** in **Accounts**, or pin them in **Settings → Servers** as described above.
- If Windows still points to a proxy after an unexpected shutdown, open Mirage again and disconnect; it restores the previous system proxy setting.

### If the internet stays blocked after Mirage is gone

Opening Mirage again lifts the kill switch's firewall block. If Mirage is no longer on this computer, lift it by hand: open PowerShell **as administrator** and run

```powershell
Set-NetFirewallProfile -Profile Domain,Private,Public -DefaultOutboundAction NotConfigured
Remove-NetFirewallRule -Group 'Mirage kill switch' -ErrorAction SilentlyContinue
Remove-Item 'HKLM:\SOFTWARE\Mirage\KillSwitch' -Recurse -ErrorAction SilentlyContinue
```

(Mirage saves your previous firewall setting under that registry key and normally puts it back exactly; `NotConfigured` is the Windows default.)

Versions from before the app was renamed used the name Relay for the same things. If the block came from one of those, also run:

```powershell
Remove-NetFirewallRule -Group 'Relay kill switch' -ErrorAction SilentlyContinue
Remove-Item 'HKLM:\SOFTWARE\Relay\KillSwitch' -Recurse -ErrorAction SilentlyContinue
```

For the Windows PowerShell tools, see [windows/README.md](windows/README.md). For app build and advanced settings, see [app/README.md](app/README.md).

## Android

1. On your phone, open the [releases page](https://github.com/siinsiw/ovpn-pin/releases) and find the newest release whose name starts with `android-v`. Under **Assets**, download `mirage-android-v….apk`.
2. Open the downloaded file. Android asks you to allow installing apps from your browser or file manager; allow it for this install.
3. Open the app, add your Surfshark or Windscribe account, and wait while it gets your servers ready. Then tap **Connect** and allow the VPN request.

Every APK is signed with the same key, so a newer one installs over the old one and keeps your settings. If Android refuses to install over the existing app, the APK was not built by this project: do not use it.

The Android app includes GPL-3.0 libraries, so the APK is distributed under the GPL. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Linux

The commands below are for Ubuntu or Debian. On another distribution, install the equivalent packages: Git, curl, OpenVPN, and iproute2. Run the commands in a terminal.

### 1. Install the required packages

```bash
sudo apt update
sudo apt install git curl openvpn iproute2 nano
```

### 2. Get the files

**With Git:**

```bash
git clone https://github.com/siinsiw/ovpn-pin.git
cd ovpn-pin
```

**Without Git:** open the [repository page](https://github.com/siinsiw/ovpn-pin), click **Code → Download ZIP**, and extract it. In your file manager, open the extracted `ovpn-pin-main` folder, right-click an empty area, and choose **Open in Terminal**. All remaining commands assume the terminal is in that folder. Keep the folder after setup: the installed command points back to it.

### 3. Add your VPN credentials

The included configs are Surfshark configs. Copy the example file, make it private, and edit the two values at its top:

```bash
cp .env.example .env
chmod 600 .env
nano .env
```

```ini
OVPN_USER=your-service-username
OVPN_PASS=your-service-password
```

Save in nano with **Ctrl+O**, **Enter**, then **Ctrl+X**. Use the Surfshark *service* credentials from its manual setup page, not your account email. If you have another provider's `.ovpn` files, copy them into `configs/` first and use the credentials that provider gives for OpenVPN.

### 4. Install the command and pin the configs

```bash
bash linux/ovpn install
```

This adds `ovpn` to `~/.local/bin`. The installer prints a PATH command if this terminal cannot find `ovpn` yet. You can keep using `./linux/ovpn` from the repository instead.

1. Make sure your original `.ovpn` files are in `configs/`. The repository already includes Surfshark files. Do not connect with these originals yet.
2. Run the pin command:

   ```bash
   ./linux/ovpn pin
   ```

3. Wait for the result. The command looks up each server over DNS-over-HTTPS, rejects non-public answers, and writes copies into `pinned/` with the public IP in each `remote` line. It can write up to four IP-specific copies per original config. It never edits `configs/`.
4. Check that files were created before connecting:

   ```bash
   ls pinned/*.ovpn | head
   grep '^remote ' pinned/*.ovpn | head
   ```

   The second command should show IP addresses after `remote`, not server hostnames. If no files were written, read the pin command's error. If DNS-over-HTTPS is blocked and you already have a local HTTP proxy, retry with `./linux/ovpn pin --proxy http://127.0.0.1:10808`, replacing the port with yours.

### 5. Connect and disconnect

```bash
./linux/ovpn connect
```

Pick a number from the displayed list. Enter your Linux password when `sudo` asks; OpenVPN needs administrator access to create the tunnel. Then:

```bash
./linux/ovpn status
./linux/ovpn stop
```

In a new terminal, `ovpn connect`, `ovpn status`, and `ovpn stop` work from anywhere if `~/.local/bin` is on your PATH.

By default `ovpn connect` sends DNS through the tunnel and turns on a kill switch (it needs `nftables`), so a dropped tunnel cannot leak your real address. `--no-set-dns` and `--no-kill-switch` turn them off; see [linux/README.md](linux/README.md#the-safety-nets).

### If it does not connect

- If the saved addresses are old, run `./linux/ovpn sync` and try again.
- If OpenVPN rejects the login, check the two values in `.env`, then run `./linux/ovpn pin` again.
- If `ovpn: command not found`, open a new terminal or use `./linux/ovpn` while in the repository folder.

More commands and Linux options: [linux/README.md](linux/README.md).

## Set up a tunnel of your own

This is optional. First complete the Windows or Linux setup above. In the Windows app, open **Settings → Your own tunnel** and follow its server setup instructions. You will need a server and a domain; the server installer prints the values to enter in Mirage. The equivalent Linux commands are in [linux/README.md](linux/README.md#the-proxy-and-your-own-tunnel).

## Licence

This repository's own code is under the [MIT licence](LICENSE). The apps also ship other people's work under their own licences, and the Android APK is distributed under GPL-3.0-or-later because it links GPL libraries: see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). What each app sends, and to whom, is in [PRIVACY.md](PRIVACY.md).
