//go:build android || linux || darwin

// The tunnel's half of split tunnelling: what goes around the exit, and how.
// What is listed, and which way round that cuts, is split.go's business;
// this file is only the doing of it.
//
// Going around is the same three things the exit path does, done on the
// phone's own line instead - each socket protected out of the tun by the
// same dialControl the exit's socket is:
//
//	TCP     dialled straight to the address the app asked for
//	UDP     carried both ways, which the exit cannot do at all - so a call
//	        or a game that is going around works fully, not as TCP-only
//	DNS     asked of the line's own resolver, for names going around, so
//	        they are answered as seen from here
package core

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"syscall"
	"time"

	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// SetSplit puts a set of rules under the running stack. nil, or one that is
// not Active, carries everything through the exit. Connections already open
// keep the way they were opened by.
func (t *Tunnel) SetSplit(s *Split) {
	if s != nil && !s.Active() {
		s = nil
	}
	t.split.Store(s)
}

// Split is the rules in force, nil when there are none.
func (t *Tunnel) Split() *Split { return t.split.Load() }

// goesStraight decides one TCP connection, before anything is dialled.
//
// Cheapest evidence first. The address is a lookup in a sorted table; the
// app is a binder call, asked only when apps are listed. Then the name, when
// names are listed and neither of those settled it - and for a destination
// that arrived as an address, the client's first message is read for it
// before the name remembered for that address is believed: the SNI in a
// ClientHello is what this connection asked for, and the remembered name is
// only the last lookup that produced the address (see ListedByName). That
// read is the ClientHello for anything over TLS, which arrives as soon as
// the stack accepts, so it costs a few milliseconds there and at most
// firstBytes' short deadline for a protocol where the server speaks first.
//
// given is the destination's own name when it arrived as one, and
// remembered the name dnsnames.go holds for its address when it did not.
//
// Returns whatever was read, so the caller can send it on: those bytes are
// the start of the connection and belong at the far end whichever way it
// goes.
func (h *handler) goesStraight(conn net.Conn, s *Split, source, destination M.Socksaddr, given, remembered string) (straight bool, first []byte, sni string) {
	ip := destination.Addr.Unmap()
	listed := destination.IsIP() && s.AddrListed(ip)
	if !listed && s.NeedsOwner() {
		listed = s.UidListed(ownerOf(ipProtoTCP, source.String(), destination.String()))
	}
	if !listed && s.NeedsName() {
		if given != "" {
			listed = s.NameListed(given)
		} else {
			if b, err := firstBytes(conn); err == nil && len(b) > 0 {
				first = b
				sni = sniFrom(b)
			}
			listed = s.ListedByName(sni, remembered)
		}
	}
	return s.Direct(listed), first, sni
}

// udpGoesStraight is the same decision for a UDP flow, made in JudgeFlow -
// where a "no" is a drop, since the exit has no UDP to offer instead. No
// first message to read here, so a name only counts if a lookup produced it.
func (h *handler) udpGoesStraight(s *Split, source, destination netip.AddrPort) bool {
	ip := destination.Addr().Unmap()
	listed := s.AddrListed(ip) || s.NameListed(h.names.Name(ip))
	if !listed && s.NeedsOwner() {
		listed = s.UidListed(ownerOf(ipProtoUDP, source.String(), destination.String()))
	}
	return s.Direct(listed)
}

// dialStraight is one TCP connection on the phone's own line.
func dialStraight(ctx context.Context, target string, timeout time.Duration) (net.Conn, error) {
	d := net.Dialer{Timeout: timeout, Control: straightGuard}
	return d.DialContext(ctx, "tcp4", target)
}

// straightGuard refuses, on the address actually dialled, what no connection
// out of the tun can mean: loopback, the unspecified address, link-local and
// multicast. Nothing the tun hands over is addressed so - which is why it is
// safe to be strict - and a dial that ends up there anyway is this process
// reaching into the phone itself. Then the socket is protected like any.
func straightGuard(network, address string, c syscall.RawConn) error {
	if ap, err := netip.ParseAddrPort(address); err == nil {
		a := ap.Addr().Unmap()
		if a.IsLoopback() || a.IsUnspecified() || a.IsLinkLocalUnicast() || a.IsMulticast() {
			return errors.New("not dialling " + address + " on the phone's own line")
		}
	}
	return dialControl(network, address, c)
}

// carryUDP carries one UDP flow both ways on the phone's own line, until the
// stack's NAT times it out or either side closes.
//
// An unconnected socket rather than one dialled to the destination, because
// a flow in the stack's sense is keyed by its source: one app socket may
// send to several addresses, and each reply has to come back labelled with
// the address it came from.
func carryUDP(ctx context.Context, conn N.PacketConn) error {
	lc := net.ListenConfig{Control: dialControl}
	pc, err := lc.ListenPacket(ctx, "udp4", ":0")
	if err != nil {
		return err
	}
	defer pc.Close()
	return bufio.CopyPacketConn(ctx, conn, bufio.NewPacketConn(pc))
}

// askStraight asks one DNS query of the line's own resolver, over plain UDP
// on a protected socket. Plain, because that is all a line's resolver is
// guaranteed to speak - and these are names that are not filtered here, so
// what the line would forge is not what is being asked.
func askStraight(resolver netip.AddrPort, query []byte) ([]byte, error) {
	d := net.Dialer{Timeout: 4 * time.Second, Control: dialControl}
	c, err := d.Dial("udp4", resolver.String())
	if err != nil {
		return nil, err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(4 * time.Second))
	if _, err := c.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 65535)
	for {
		n, err := c.Read(buf)
		if err != nil {
			return nil, err
		}
		// Only the answer to this query: the same ID. Anything else on the
		// socket is late from somewhere and not this question's business.
		if n >= 12 && len(query) >= 2 && buf[0] == query[0] && buf[1] == query[1] {
			return buf[:n], nil
		}
	}
}

// askDNSFor asks a query whichever way its name goes: the line's own
// resolver for a name going around, the exit for everything else - and the
// exit as well when the line's resolver does not answer, because an answer
// from the far side still lets the connection go straight; no answer at all
// lets nothing.
//
// Under Iran direct (Smart), an A query for a name on no list is asked both
// ways at once, and the line's answer wins when it puts the name in Iran.
// goesStraight checks the address before anything else, so the connection
// that follows goes straight with nothing else remembered.
func (h *handler) askDNSFor(query []byte) ([]byte, error) {
	s := (*Tunnel)(h).Split()
	if s == nil {
		return h.askDNS(query)
	}
	resolver, ok := s.Resolver()
	if !ok {
		return h.askDNS(query)
	}
	if s.DirectDNS(questionName(query)) {
		if answer, err := askStraight(resolver, query); err == nil && len(answer) > 0 {
			return answer, nil
		}
		return h.askDNS(query)
	}
	// A only: the tun carries no IPv6, and an AAAA from the line would be an
	// address nothing here can reach straight.
	if s.Smart() && questionType(query) == 1 {
		return askEitherWay(query,
			func(q []byte) ([]byte, error) { return askStraight(resolver, q) },
			h.askDNS)
	}
	return h.askDNS(query)
}

// smartGrace is how long the exit's answer, once in, waits for the line's.
// The line's resolver is a hop away and nearly always first; when it is not,
// waiting for it the full four seconds would hold up every name abroad, and
// an Iranian name answered from the exit side still mostly lands on an
// Iranian address, which goes straight anyway.
const smartGrace = 400 * time.Millisecond

// askEitherWay asks the line and the exit at once and picks: the line's
// answer when it puts the name on an Iranian public address, the exit's
// otherwise. A forged answer (10.10.34.x) is private, so a filtered name
// always comes back from the exit, never from the line.
//
// The asks are passed in so the picking can be tested without a network.
func askEitherWay(query []byte, line, exit func([]byte) ([]byte, error)) ([]byte, error) {
	type result struct {
		answer []byte
		err    error
	}
	fromLine := make(chan result, 1)
	fromExit := make(chan result, 1)
	go func() { a, err := line(query); fromLine <- result{a, err} }()
	go func() { a, err := exit(query); fromExit <- result{a, err} }()

	var (
		exitDone *result
		grace    <-chan time.Time
	)
	for {
		select {
		case r := <-fromLine:
			if r.err == nil && answerInIran(r.answer) {
				return r.answer, nil
			}
			if exitDone == nil {
				e := <-fromExit
				return e.answer, e.err
			}
			return exitDone.answer, exitDone.err
		case e := <-fromExit:
			exitDone = &e
			fromExit = nil // received once; a nil channel is never ready again
			t := time.NewTimer(smartGrace)
			defer t.Stop()
			grace = t.C
		case <-grace:
			return exitDone.answer, exitDone.err
		}
	}
}
