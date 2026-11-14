# Third-party notices

This repository's own code is under the MIT licence in [LICENSE](LICENSE).
The apps built from it also contain other people's work, under the licences
below. Where a licence asks for its text to travel with the copy, the file
named here is shipped beside it.

## The Android app is distributed under GPL-3.0-or-later

The Android app links these GPL libraries into its binary through gomobile:

| Library | Licence |
| --- | --- |
| [sagernet/sing](https://github.com/SagerNet/sing) | GPL-3.0-or-later |
| [sagernet/sing-tun](https://github.com/SagerNet/sing-tun) | GPL-3.0-or-later |
| [sagernet/sing-box](https://github.com/SagerNet/sing-box) - the Configs section's outbounds and groups only (`mobile/core/configs_box.go`), with the sagernet modules it brings (sing-vmess, sing-shadowsocks, sing-quic, quic-go fork, uTLS fork) | GPL-3.0-or-later; its own dependencies under their licences, as pinned in `go.sum` |

A program that includes GPL-3.0 code is distributed under the GPL as a whole.
So the Android APK, as released, is under the
[GNU General Public License, version 3 or later](https://www.gnu.org/licenses/gpl-3.0.html).
The MIT licence still covers this repository's own source files, which can
be reused on their own under MIT.

The corresponding source for each APK is this repository at that APK's
`android-v*` release tag, together with the Go module versions pinned in
[`mobile/core/go.mod`](mobile/core/go.mod) and
[`mobile/core/go.sum`](mobile/core/go.sum).

The Go core also uses these libraries, under permissive licences:
`gorilla/websocket` (BSD-2-Clause), `shadowsocks/go-shadowsocks2` (Apache-2.0),
`xtaci/smux` (MIT), `rsc.io/qr` (BSD-3-Clause), and the `golang.org/x`
modules (BSD-3-Clause). AndroidX and Jetpack Compose are Apache-2.0.

## Programs shipped beside the Windows app

| Program | Licence | Source |
| --- | --- | --- |
| `sing-box.exe` | GPL-3.0-or-later; its licence ships as `sing-box-LICENSE.txt` | [SagerNet/sing-box](https://github.com/SagerNet/sing-box), at the version named by `SINGBOX_VERSION` in `.github/workflows/desktop-release.yml` |
| `gost.exe` | MIT | [go-gost/gost](https://github.com/go-gost/gost), at the version named by `GOST_VERSION` in the same file |
| `xray.exe` | MPL-2.0; its licence ships as `xray-LICENSE.txt` | [XTLS/Xray-core](https://github.com/XTLS/Xray-core), at the version named by `XRAY_VERSION` in the same file, checked against the SHA2-256 Xray publishes beside the archive |

Relay starts all three as separate programs and does not link them in, so
the Windows app itself stays under MIT. The Configs section runs sing-box
(and, for xhttp, Xray) as its engine; the provider side runs gost and, in
Tunnel mode, sing-box.

## Inside the Windows window (`app/ui`)

| Component | Licence | Notice |
| --- | --- | --- |
| GSAP 3.13 (`gsap.min.js`) | GSAP Standard "no charge" licence, not an open-source licence: [gsap.com/standard-license](https://gsap.com/standard-license) | header of the file |
| three.js r170 (`three.min.js`) | MIT | header of the file |
| qrcode-generator 1.4.4 (`qrcode.min.js`) | MIT | header of the file |
| Fuse.js 7.0 (`fuse.js`) | Apache-2.0 | `FUSE-LICENSE.txt` |
| Lucide icons | ISC | `LUCIDE-LICENSE.txt` |
| flag-icons (`flags/`) | MIT | `flags/LICENSE` |
| Geist, Geist Mono, Estedad, Cinzel | SIL Open Font License 1.1 | `fonts/OFL-*.txt` |

## Inside the Android app (`android/app/src/main/assets`)

| Component | Licence | Notice |
| --- | --- | --- |
| flag-icons (`flags/`, `flags43/`) | MIT | `flags/LICENSE` |
| Geist, Geist Mono, Estedad, Cinzel | SIL Open Font License 1.1 | `OFL-*.txt` |

## Provider configs

The `.ovpn` files in `configs/` are Surfshark's published OpenVPN
configurations. The CA certificate and the `tls-auth` key in them are the
same public values in every Surfshark config. They are not this project's,
and Surfshark has not said whether they may be redistributed. The Windows
app can fetch Surfshark's list itself (Settings → Accounts → Get servers),
so these bundled files are a convenience, not a requirement.
