//go:build android || linux || darwin

package core

// UDP through the way out, when the way out can carry it.
//
// The provider exits are HTTPS proxies and CONNECT carries a stream, so for
// them UDP stays what tunnel.go says it is: dropped, and QUIC falls back to
// TCP. A config is not one of those. VLESS, VMess, Trojan, Shadowsocks,
// Hysteria2 and TUIC all carry datagrams, and with them calls, games and
// QUIC work as they do on any other VPN - so a flow that is not going
// around the exit (split tunnelling still decides that first) is handed to
// the way's own ListenPacket.

import (
	"context"

	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"

	"github.com/sagernet/sing/common/bufio"
)

// packetWay is what is carrying now, if it carries UDP.
func (h *handler) packetWay() PacketWay {
	if b := (*Tunnel)(h).exit.Load(); b != nil {
		if pw, ok := b.Way.(PacketWay); ok {
			return pw
		}
	}
	return nil
}

// packetVia carries one UDP flow through the way, and says whether it took
// it. A flow split tunnelling sends around the exit is left to the caller,
// which carries it on the phone's own line as before.
func (h *handler) packetVia(ctx context.Context, conn N.PacketConn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) bool {
	pw := h.packetWay()
	if pw == nil {
		return false
	}
	if s := (*Tunnel)(h).Split(); s != nil && h.udpGoesStraight(s, source.AddrPort(), destination.AddrPort()) {
		return false
	}
	go func() {
		err := carryUDPVia(ctx, conn, pw, destination)
		conn.Close()
		if onClose != nil {
			onClose(err)
		}
	}()
	return true
}

func carryUDPVia(ctx context.Context, conn N.PacketConn, pw PacketWay, destination M.Socksaddr) error {
	pc, err := pw.ListenPacket(destination.AddrPort())
	if err != nil {
		return err
	}
	defer pc.Close()
	return bufio.CopyPacketConn(ctx, conn, bufio.NewPacketConn(pc))
}
