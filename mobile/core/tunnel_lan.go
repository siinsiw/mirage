//go:build android || linux || darwin

package core

import (
	"context"
	"net"
	"net/netip"
	"time"
)

// tunnelLAN is a running tunnel as the LAN proxy sees it.
type tunnelLAN struct{ t *Tunnel }

// LANBackend is this tunnel's way out, offered to a LANProxy. It follows the
// tunnel: when SetWay replaces the exit, connections the proxy opens from
// then on go through the new one, exactly as the phone's own do.
func (t *Tunnel) LANBackend() LANBackend { return tunnelLAN{t} }

func (b tunnelLAN) Open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	return b.t.open(target, timeout)
}

// Straight asks split tunnelling the same questions the phone's own
// connections are asked, as far as they can be asked of a device that is not
// this one: the address, and the name (given, or remembered from the DNS
// answer that produced the address). The owning app cannot be asked - the
// connection belongs to no app here - so in "only these apps" mode a shared
// device is not one of them, and goes straight.
//
// For an address, the SNI the proxy read from the client's ClientHello
// decides ahead of the name remembered for that address, for the reason
// ListedByName gives; the remembered name only when there was no SNI.
func (b tunnelLAN) Straight(host, sni string) bool {
	s := b.t.Split()
	if s == nil {
		return false
	}
	listed := false
	if ip, err := netip.ParseAddr(host); err == nil {
		listed = s.AddrListed(ip)
		if !listed {
			remembered := ""
			if b.t.names != nil {
				remembered = b.t.names.Name(ip.Unmap())
			}
			listed = s.ListedByName(sni, remembered)
		}
	} else {
		listed = s.NameListed(host)
	}
	return s.Direct(listed)
}

func (b tunnelLAN) NameMatters() bool {
	s := b.t.Split()
	return s != nil && s.NeedsName()
}

// DialStraight is dialStraight with lanDialGuard in place of the plain
// protector: the phone's own line reaches the phone's own services, and a
// shared device has no business there.
func (b tunnelLAN) DialStraight(ctx context.Context, target string, timeout time.Duration) (net.Conn, error) {
	d := net.Dialer{Timeout: timeout, Control: lanDialGuard}
	return d.DialContext(ctx, "tcp4", target)
}

func (b tunnelLAN) Hosts() *Hosts { return b.t.hosts }
