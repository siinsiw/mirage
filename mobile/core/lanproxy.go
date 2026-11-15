package core

import (
	"bufio"
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// Sharing the phone's way out with the devices around it.
//
// An iPhone on the same Wi-Fi - or one joined to this phone's hotspot - has
// a setting for an HTTP proxy, and nothing else it needs. Point it at the
// phone's address and a port, and its traffic leaves by whatever Mirage is
// connected through, with no app installed on it and, above all, no
// credential on it: the exit's sign-in, the user's own server's key and the
// pinned addresses all stay in this process. The device being helped only
// ever learns an IP and a port.
//
// This is core/ovpn-proxy.py's serve_one, for the same reason it exists
// there: one byte decides which protocol is being spoken. SOCKS5 opens with
// its version, 0x05; every HTTP verb opens with a letter. So both fit on one
// port, and a client can be pointed at either without anything being
// reconfigured on this end.
//
// Two things here are easy to get backwards.
//
// Outbound goes through the Way, never straight. The listener's own sockets
// are in this app's process, which Android excludes from the VPN - so a
// plain net.Dial from here would leave by the bare line, to the very place
// the sharing was turned on to avoid, and it would work, which is the
// dangerous part: nothing would look wrong. The only exception is the one
// split tunnelling makes for the phone itself, taken by the same rules and
// dialled the same way (protected, see dialControl) so a shared device
// behaves like the phone it borrows.
//
// And the listener is open to the whole network it is on, which on a cafe's
// Wi-Fi is strangers. So:
//
//   - it listens only on the phone's Wi-Fi, hotspot, USB/Bluetooth tether and
//     Ethernet addresses - one listener per address, never 0.0.0.0, and never
//     a cellular interface, whose address is the carrier's network;
//   - a connection from anywhere but a private address in the subnet of one
//     of those interfaces is closed before a byte is read from it;
//   - a password is not optional. Sharing does not start without one.
//
// And what a client may ask for is limited too: nothing on the phone itself
// (its loopback, its own addresses), nothing link-local, and nothing on the
// far side's loopback - through the user's own server, 127.0.0.1 is that
// server's API.

// LANBackend is what the proxy needs from the running tunnel, and nothing
// more - so the protocol handling can be tested against a fake, and builds on
// the platforms that have no tunnel at all.
type LANBackend interface {
	// Open is one connection through the way out that is carrying now, with
	// whatever arrived behind the answer. Counted towards that way's health.
	Open(target string, timeout time.Duration) (net.Conn, []byte, error)
	// Straight is whether split tunnelling sends this destination around the
	// way out, on the phone's own line. host is a name or an address; sni is
	// the name in the client's TLS ClientHello when one was read, which for
	// an address decides ahead of any name remembered for it.
	Straight(host, sni string) bool
	// NameMatters is whether a name could change Straight's answer for an
	// address - whether it is worth reading a client's ClientHello for one.
	NameMatters() bool
	// DialStraight is one connection on the phone's own line. It refuses
	// what lanRefusedAddr refuses, whatever name the target was given as.
	DialStraight(ctx context.Context, target string, timeout time.Duration) (net.Conn, error)
	// Hosts is where connections are counted; nil is allowed.
	Hosts() *Hosts
}

const (
	// LANProxyUser is the user name an HTTP client must give when a password
	// is set. SOCKS5 is not held to it: its RFC 1929 field is whatever the
	// client's settings box calls it, and only the password is the secret.
	LANProxyUser = "mirage"

	lanMaxConns    = 256
	lanHandshake   = 30 * time.Second
	lanIdle        = 5 * time.Minute
	lanDialTimeout = 15 * time.Second

	// LANMinPassword is the shortest password sharing starts with. The
	// proxy is reachable by everyone on the network it is on, and its
	// password is the only thing between them and the way out.
	LANMinPassword = 8
	// The longest, the same 128 the Windows app and the Android screen hold
	// it to - and well inside the 255 bytes RFC 1929 can carry.
	LANMaxPassword = 128

	// How often the interfaces are looked at again, so a hotspot turned on
	// after sharing started is listened on, and an address that went away is
	// not held.
	lanRescan = 5 * time.Second
	// How long a ClientHello is waited for, when one is read to decide.
	lanPeek = 400 * time.Millisecond
	// Accept errors in a row, at the longest back-off, before a listener is
	// given up on (about a minute).
	lanAcceptGiveUp = 60
)

// LANProxy is the listeners sharing a backend's way out: one per address.
type LANProxy struct {
	be       LANBackend
	password string

	// Overridable in tests: who may connect, how long things may idle, the
	// interfaces there are, the phone's own addresses, and how often the
	// interfaces are looked at again.
	allow  func(net.IP) bool
	idle   time.Duration
	ifaces func() []lanIface
	own    func() []netip.Addr
	rescan time.Duration

	mu      sync.Mutex
	lns     map[netip.Addr]net.Listener // by the address listened on
	port    int
	nets    []netip.Prefix // the subnets of the interfaces listened on
	scanned time.Time
	conns   map[net.Conn]struct{}
	started bool
	closed  bool
	stop    chan struct{}

	live atomic.Int32
	wg   sync.WaitGroup
}

// NewLANProxy builds one that is not listening yet. The password is checked
// when it starts: see LANMinPassword.
func NewLANProxy(be LANBackend, password string) *LANProxy {
	p := &LANProxy{
		be: be, password: password,
		idle: lanIdle, ifaces: systemIfaces, own: ownAddrs, rescan: lanRescan,
		lns:   map[netip.Addr]net.Listener{},
		conns: map[net.Conn]struct{}{},
		stop:  make(chan struct{}),
	}
	p.allow = p.fromLAN
	return p
}

// CheckLANPassword is whether a password is one sharing may start with: 8 to
// 128 printable ASCII characters (space to tilde, 0x20-0x7E) - the rule the
// Windows app and the Android screen share. ASCII because an HTTP client's
// proxy box and a SOCKS client's each encode anything else their own way,
// and a password one device cannot type the same as another is no password.
func CheckLANPassword(password string) error {
	if len(password) < LANMinPassword || len(password) > LANMaxPassword {
		return fmt.Errorf("sharing needs a password of %d to %d characters", LANMinPassword, LANMaxPassword)
	}
	for i := 0; i < len(password); i++ {
		if c := password[i]; c < 0x20 || c > 0x7e {
			return errors.New("the sharing password has a character that is not printable ASCII")
		}
	}
	return nil
}

// Start listens on TCP port on each of this device's shareable addresses -
// Wi-Fi, hotspot, tether, Ethernet; see shareable - and keeps that set up to
// date while it runs. Port 0 takes whatever port the first address is given
// and uses it for the rest.
//
// "tcp4": the tun is IPv4 only, the addresses shown to the user are IPv4,
// and a listener that also answered on IPv6 would be reachable by a family
// the source filter was never written for.
func (p *LANProxy) Start(port int) error {
	if err := CheckLANPassword(p.password); err != nil {
		return err
	}
	if port < 0 || port > 65535 {
		return errors.New("that is not a port")
	}
	addrs, nets := lanListenAddrs(p.ifaces())
	if len(addrs) == 0 {
		return errors.New("there is no Wi-Fi, hotspot or Ethernet address to share on - " +
			"join a Wi-Fi network or turn the hotspot on first")
	}

	p.mu.Lock()
	if p.closed || p.started {
		p.mu.Unlock()
		return errors.New("this proxy was already started")
	}
	p.started = true
	p.port = port
	p.nets, p.scanned = nets, time.Now()
	var first error
	for _, a := range addrs {
		if err := p.listenLocked(a); err != nil && first == nil {
			first = err
		}
	}
	if len(p.lns) == 0 {
		p.mu.Unlock()
		p.Close()
		if first == nil {
			first = errors.New("nothing could be listened on")
		}
		return first
	}
	p.wg.Add(1)
	p.mu.Unlock()
	go p.watch()
	return nil
}

// listenLocked opens one listener on addr at the proxy's port, and starts
// accepting on it. p.mu is held.
func (p *LANProxy) listenLocked(addr netip.Addr) error {
	if _, ok := p.lns[addr]; ok {
		return nil
	}
	ln, err := net.Listen("tcp4", netip.AddrPortFrom(addr, uint16(p.port)).String())
	if err != nil {
		return err
	}
	if p.port == 0 {
		if a, ok := ln.Addr().(*net.TCPAddr); ok {
			p.port = a.Port
		}
	}
	p.lns[addr] = ln
	p.wg.Add(1)
	go p.accept(addr, ln)
	return nil
}

// watch looks at the interfaces again every so often, until Close: a new
// shareable address is listened on, one that went away is let go.
func (p *LANProxy) watch() {
	defer p.wg.Done()
	t := time.NewTicker(p.rescan)
	defer t.Stop()
	for {
		select {
		case <-p.stop:
			return
		case <-t.C:
		}
		addrs, nets := lanListenAddrs(p.ifaces())
		want := map[netip.Addr]bool{}
		for _, a := range addrs {
			want[a] = true
		}
		var gone []net.Listener
		p.mu.Lock()
		if p.closed {
			p.mu.Unlock()
			return
		}
		p.nets, p.scanned = nets, time.Now()
		for a, ln := range p.lns {
			if !want[a] {
				gone = append(gone, ln)
				delete(p.lns, a)
			}
		}
		for _, a := range addrs {
			// A failure is tried again next time round: the address may not
			// be bindable yet in the moment the interface appears.
			_ = p.listenLocked(a)
		}
		p.mu.Unlock()
		for _, ln := range gone {
			ln.Close()
		}
	}
}

// Serve runs on one listener the caller made, with no interfaces looked at:
// what the tests use. Returns at once; the accepting is on a goroutine of
// its own. The password is held to the same rule as Start's.
func (p *LANProxy) Serve(ln net.Listener) error {
	if err := CheckLANPassword(p.password); err != nil {
		ln.Close()
		return err
	}
	a, ok := ln.Addr().(*net.TCPAddr)
	var addr netip.Addr
	if ok {
		addr, ok = netip.AddrFromSlice(a.IP)
	}
	p.mu.Lock()
	if p.closed || p.started || !ok {
		p.mu.Unlock()
		ln.Close()
		return errors.New("this proxy was already started")
	}
	p.started = true
	p.port = a.Port
	addr = addr.Unmap()
	p.lns[addr] = ln
	p.wg.Add(1)
	p.mu.Unlock()
	go p.accept(addr, ln)
	return nil
}

// Port is the port listened on, 0 when not.
func (p *LANProxy) Port() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.lns) == 0 {
		return 0
	}
	return p.port
}

// Running is whether anything is listening now. False once Close has run,
// and also when every listener has gone - the network it was on went away
// and has not come back, or accepting failed for good - which is the
// sharing having stopped, whatever the screen last showed.
func (p *LANProxy) Running() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return !p.closed && len(p.lns) > 0
}

// Addrs is the addresses being listened on, for the screen to show next to
// the port.
func (p *LANProxy) Addrs() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]string, 0, len(p.lns))
	for a := range p.lns {
		out = append(out, a.String())
	}
	sort.Strings(out)
	return out
}

// Clients is how many connections are being carried right now.
func (p *LANProxy) Clients() int { return int(p.live.Load()) }

// Close stops listening and closes every connection in flight. Safe to call
// twice. Waits for the goroutines to finish, which they do promptly: every
// one of them is blocked on a socket that was just closed.
func (p *LANProxy) Close() error {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return nil
	}
	p.closed = true
	close(p.stop)
	lns := make([]net.Listener, 0, len(p.lns))
	for a, ln := range p.lns {
		lns = append(lns, ln)
		delete(p.lns, a)
	}
	open := make([]net.Conn, 0, len(p.conns))
	for c := range p.conns {
		open = append(open, c)
	}
	p.mu.Unlock()
	for _, ln := range lns {
		ln.Close()
	}
	for _, c := range open {
		c.Close()
	}
	p.wg.Wait()
	return nil
}

// track remembers a connection so Close can reach it; false if closing has
// begun, in which case the caller must drop it.
func (p *LANProxy) track(c net.Conn) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return false
	}
	p.conns[c] = struct{}{}
	return true
}

func (p *LANProxy) untrack(c net.Conn) {
	p.mu.Lock()
	delete(p.conns, c)
	p.mu.Unlock()
}

// trackUp is track for the connection on the far side. Without it, Close
// would reach the client's socket but not the one dialled for it, and a
// goroutine blocked in a dial or a copy on the far side would hold Close up
// for as long as that took.
func (p *LANProxy) trackUp(c net.Conn) bool { return p.track(c) }

// accept takes connections on one listener until it is closed.
//
// An error from Accept is not the end of it. Most are the process or the
// kernel being short of something for a moment - EMFILE when every
// descriptor is in use, ENFILE, ECONNABORTED for a client that left
// mid-handshake - and the listener is fine; returning on the first of them
// would stop sharing for good while the screen still said it was on. So it
// backs off, up to a second, and carries on. Only a listener that keeps
// failing for about a minute is given up on: it is closed and dropped, and
// Running says so once none are left (the next rescan listens again if the
// address is still there).
func (p *LANProxy) accept(addr netip.Addr, ln net.Listener) {
	defer p.wg.Done()
	var delay time.Duration
	failed := 0
	for {
		c, err := ln.Accept()
		if err != nil {
			if errors.Is(err, net.ErrClosed) || p.isClosed() {
				return
			}
			if delay == 0 {
				delay = 5 * time.Millisecond
			} else if delay *= 2; delay >= time.Second {
				delay = time.Second
				failed++
			}
			if failed >= lanAcceptGiveUp {
				log.Printf("mirage: sharing on %s stopped: %v", addr, err)
				p.mu.Lock()
				if p.lns[addr] == ln {
					delete(p.lns, addr)
				}
				p.mu.Unlock()
				ln.Close()
				return
			}
			select {
			case <-time.After(delay):
			case <-p.stop:
				return
			}
			continue
		}
		delay, failed = 0, 0
		// Refused before anything is read. A stranger learns nothing from a
		// reset, and costs a goroutine for as long as it takes to close.
		ip := remoteIP(c)
		if ip == nil || !p.allow(ip) || int(p.live.Load()) >= lanMaxConns {
			c.Close()
			continue
		}
		p.live.Add(1)
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			defer p.live.Add(-1)
			p.serve(c, ip.String())
		}()
	}
}

func remoteIP(c net.Conn) net.IP {
	a, ok := c.RemoteAddr().(*net.TCPAddr)
	if !ok {
		return nil
	}
	return a.IP
}

func (p *LANProxy) isClosed() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.closed
}

// PrivateSource is whether an address is one a device on a home, office or
// hotspot network would have: 10/8, 172.16/12, 192.168/16. IPv4 only, like
// everything else here.
//
// Not carrier-grade NAT (100.64/10) and not link-local (169.254/16), which
// this once let in: the first is the address a cellular network hands out,
// so admitting it admitted the carrier's other customers, and the second is
// what a device has when nothing gave it one - never a hotspot client.
func PrivateSource(ip net.IP) bool {
	v4 := ip.To4()
	if v4 == nil {
		return false
	}
	switch {
	case v4[0] == 10:
		return true
	case v4[0] == 172 && v4[1]&0xf0 == 16:
		return true
	case v4[0] == 192 && v4[1] == 168:
		return true
	}
	return false
}

// fromLAN is the default source filter: a private address inside the subnet
// of an interface this proxy listens on. A 10.x address on the far side of
// the carrier's network is private too, and is not a neighbour.
//
// A miss looks at the interfaces again, at most once a second - a hotspot
// client can arrive in the seconds between a hotspot coming up and the next
// rescan.
func (p *LANProxy) fromLAN(ip net.IP) bool {
	if !PrivateSource(ip) {
		return false
	}
	a, ok := netip.AddrFromSlice(ip.To4())
	if !ok {
		return false
	}
	p.mu.Lock()
	nets, stale := p.nets, time.Since(p.scanned) > time.Second
	p.mu.Unlock()
	if inPrefixes(nets, a) {
		return true
	}
	if !stale || p.ifaces == nil {
		return false
	}
	_, nets = lanListenAddrs(p.ifaces())
	p.mu.Lock()
	p.nets, p.scanned = nets, time.Now()
	p.mu.Unlock()
	return inPrefixes(nets, a)
}

func inPrefixes(nets []netip.Prefix, a netip.Addr) bool {
	for _, n := range nets {
		if n.Contains(a) {
			return true
		}
	}
	return false
}

// lanIface is one network interface, as far as sharing cares: its name and
// its addresses with their prefix lengths.
type lanIface struct {
	Name  string
	Addrs []netip.Prefix
}

// systemIfaces is this device's interfaces that are up, loopback left out.
func systemIfaces() []lanIface {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var out []lanIface
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		li := lanIface{Name: ifc.Name}
		for _, a := range addrs {
			n, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip, ok := netip.AddrFromSlice(n.IP)
			if !ok {
				continue
			}
			ones, _ := n.Mask.Size()
			ip = ip.Unmap()
			if ip.Is4() && ones > 32 {
				ones -= 96
			}
			li.Addrs = append(li.Addrs, netip.PrefixFrom(ip, ones))
		}
		out = append(out, li)
	}
	return out
}

// Interface names, by what they are. Android has no API a Go library can ask
// "is this cellular", so the name is the evidence - and the rule is both
// ways round: a name has to look like Wi-Fi, a hotspot, a tether or
// Ethernet, AND must not look like a modem. An interface nobody listed is
// not shared, which is the safe way to be wrong.
var (
	lanSharePrefixes = []string{
		"wlan", "swlan", "wl", "wifi", "ap", "softap", "p2p", // Wi-Fi, hotspot, Wi-Fi Direct
		"eth", "en", // Ethernet (en: macOS and predictable Linux names)
		"rndis", "usb", "ncm", // USB tethering
		"bt-pan", "bnep", // Bluetooth tethering
	}
	lanCellularPrefixes = []string{
		"rmnet", "r_rmnet", "rev_rmnet", "ccmni", "ccemni", "cc2mni", "pdp", "seth",
		"sipa", "wwan", "ppp", "clat", "v4-", "epdg", "ims", "uwbr",
		"tun", "tap", "ipsec", "dummy", "lo", "ifb", "sit", "gre", "ip6", "veth",
	}
)

// shareable is whether sharing may listen on an interface of this name.
func shareable(name string) bool {
	n := strings.ToLower(name)
	for _, pre := range lanCellularPrefixes {
		if strings.HasPrefix(n, pre) {
			return false
		}
	}
	for _, pre := range lanSharePrefixes {
		if strings.HasPrefix(n, pre) {
			return true
		}
	}
	return false
}

// lanListenAddrs is what to listen on: every private IPv4 address of every
// shareable interface - and the subnets those addresses are in, which are
// where a client may come from.
func lanListenAddrs(ifs []lanIface) (addrs []netip.Addr, nets []netip.Prefix) {
	seen := map[netip.Addr]bool{}
	for _, ifc := range ifs {
		if !shareable(ifc.Name) {
			continue
		}
		for _, p := range ifc.Addrs {
			a := p.Addr().Unmap()
			if !a.Is4() || !PrivateSource(net.IP(a.AsSlice())) || seen[a] {
				continue
			}
			seen[a] = true
			addrs = append(addrs, a)
			bits := p.Bits()
			if bits < 8 || bits > 32 {
				// No usable prefix length: the address alone, which lets
				// nobody else in rather than everybody.
				bits = 32
			}
			nets = append(nets, netip.PrefixFrom(a, bits).Masked())
		}
	}
	sort.Slice(addrs, func(i, j int) bool { return addrs[i].Less(addrs[j]) })
	return addrs, nets
}

// LocalAddrs lists the addresses sharing would listen on, for a screen to
// show before it starts. Once it has, LANProxy.Addrs is what is listened on.
func LocalAddrs() []string {
	addrs, _ := lanListenAddrs(systemIfaces())
	out := make([]string, 0, len(addrs))
	for _, a := range addrs {
		out = append(out, a.String())
	}
	return out
}

// ownAddrs is every address this device has, on every interface - the
// cellular ones included, since a client asking for any of them is asking
// for the phone itself.
func ownAddrs() []netip.Addr {
	as, err := net.InterfaceAddrs()
	if err != nil {
		return nil
	}
	var out []netip.Addr
	for _, a := range as {
		if n, ok := a.(*net.IPNet); ok {
			if ip, ok := netip.AddrFromSlice(n.IP); ok {
				out = append(out, ip.Unmap())
			}
		}
	}
	return out
}

// lanRefusedAddr is whether a shared device may not be connected to ip,
// either way out: the phone's own loopback or addresses (whatever listens on
// the phone is not the network's to use), anything link-local, the
// unspecified address, and multicast. Through the way out, loopback is the
// far end's own - on the user's server, where its API listens.
func lanRefusedAddr(ip netip.Addr, own []netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsValid() || ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsInterfaceLocalMulticast() {
		return true
	}
	if ip.Is4() {
		if b := ip.As4(); b[0] == 0 || b == [4]byte{255, 255, 255, 255} {
			return true // "this network", which Linux dials as itself; and broadcast
		}
	}
	for _, o := range own {
		if o.Unmap() == ip {
			return true
		}
	}
	return false
}

// lanDialGuard is net.Dialer.Control for a shared device's straight
// connections: lanRefusedAddr applied to the address actually being dialled,
// after any name was resolved - so a name that resolves to 127.0.0.1 is
// refused as surely as the address would have been - and then the socket
// kept out of the tun like every other.
func lanDialGuard(network, address string, c syscall.RawConn) error {
	ap, err := netip.ParseAddrPort(address)
	if err != nil || lanRefusedAddr(ap.Addr(), ownAddrs()) {
		return errors.New("a shared device may not connect to " + address)
	}
	return dialControl(network, address, c)
}

// checkLANTarget is what a shared device may ask for, before any dial: a
// target that can be written into a CONNECT line (checkTarget), and not one
// lanRefusedAddr refuses - given as an address, or as a name that is the
// loopback by definition.
func (p *LANProxy) checkLANTarget(target string) error {
	if err := checkTarget(target); err != nil {
		return err
	}
	host, _, _ := net.SplitHostPort(target)
	if a, err := netip.ParseAddr(host); err == nil {
		var own []netip.Addr
		if p.own != nil {
			own = p.own()
		}
		if lanRefusedAddr(a, own) {
			return errors.New("a shared device may not connect to " + target)
		}
		return nil
	}
	name := strings.TrimSuffix(strings.ToLower(host), ".")
	if name == "localhost" || strings.HasSuffix(name, ".localhost") {
		return errors.New("a shared device may not connect to " + target)
	}
	if !validLANName(name) {
		return errors.New("that destination is not a name or an address")
	}
	return nil
}

// validLANName is a hostname as a client may give one: letters, digits,
// hyphens and underscores in dot-separated labels, 253 at most.
func validLANName(name string) bool {
	if name == "" || len(name) > 253 {
		return false
	}
	for _, label := range strings.Split(name, ".") {
		if label == "" || len(label) > 63 {
			return false
		}
		for i := 0; i < len(label); i++ {
			c := label[i]
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
				return false
			}
		}
	}
	return true
}

// idleConn pushes the deadline forward on every read and write, so what
// ends a connection is silence rather than age: a download that runs for an
// hour is fine, a client that vanished is not kept for an hour.
type idleConn struct {
	net.Conn
	d atomic.Int64
}

func (c *idleConn) Read(b []byte) (int, error) {
	_ = c.Conn.SetDeadline(time.Now().Add(time.Duration(c.d.Load())))
	return c.Conn.Read(b)
}

func (c *idleConn) Write(b []byte) (int, error) {
	_ = c.Conn.SetDeadline(time.Now().Add(time.Duration(c.d.Load())))
	return c.Conn.Write(b)
}

// serve is one client, start to finish.
func (p *LANProxy) serve(raw net.Conn, clientIP string) {
	conn := &idleConn{Conn: raw}
	conn.d.Store(int64(lanHandshake))
	if !p.track(conn) {
		raw.Close()
		return
	}
	defer p.untrack(conn)
	defer conn.Close()

	br := bufio.NewReaderSize(conn, 4096)
	first, err := br.Peek(1)
	if err != nil {
		return
	}
	if first[0] == 5 {
		p.serveSOCKS(conn, br, clientIP)
	} else {
		p.serveHTTP(conn, br, clientIP)
	}
}

// dial opens the far side of one client connection, the way the phone's own
// apps' connections are opened: through the way out, or around it when
// split tunnelling says so. sni is the name in the client's ClientHello when
// one was read before deciding, "" otherwise. Counted before the dial, for
// the reason carry gives: a destination that refuses should still appear in
// the list.
func (p *LANProxy) dial(target, sni, clientIP string) (net.Conn, []byte, *hostRow, error) {
	if err := p.checkLANTarget(target); err != nil {
		return nil, nil, nil, err
	}
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return nil, nil, nil, err
	}
	hosts := p.be.Hosts()
	row := hosts.opened(target, "", "LAN "+clientIP)
	var (
		up    net.Conn
		spare []byte
	)
	if p.be.Straight(host, sni) {
		up, err = p.be.DialStraight(context.Background(), target, lanDialTimeout)
		if err == nil {
			hosts.straight(row)
		}
	} else {
		up, spare, err = p.be.Open(target, lanDialTimeout)
	}
	if err != nil {
		hosts.closed(row)
		return nil, nil, nil, err
	}
	if !p.trackUp(up) {
		up.Close()
		hosts.closed(row)
		return nil, nil, nil, errors.New("the proxy is closing")
	}
	return up, spare, row, nil
}

// pipe carries bytes both ways until either side ends, counting as they
// pass. client is the reader's view of the client - it may hold bytes
// already buffered - and conn the raw connection to write back on.
func (p *LANProxy) pipe(conn *idleConn, client io.Reader, up net.Conn, row *hostRow) {
	conn.d.Store(int64(p.idle))
	hosts := p.be.Hosts()
	toUp := counted{w: up, h: hosts, row: row}
	toClient := counted{w: conn, h: hosts, row: row, down: true}
	done := make(chan struct{}, 2)
	go func() { io.Copy(toUp, client); done <- struct{}{} }()
	go func() { io.Copy(toClient, up); done <- struct{}{} }()
	<-done
	// Closing both is what ends the other copy, so the wait is bounded.
	up.Close()
	conn.Close()
	<-done
}

// finish releases the far side once a client is done with it.
func (p *LANProxy) finish(up net.Conn, row *hostRow) {
	up.Close()
	p.untrack(up)
	p.be.Hosts().closed(row)
}

// wantsHello is whether to read the client's ClientHello before deciding
// which way a target goes: only for a target given as an address, and only
// when split tunnelling has names a ClientHello could match. A target given
// as a name is decided by that name.
func (p *LANProxy) wantsHello(target string) bool {
	host, _, err := net.SplitHostPort(target)
	if err != nil {
		return false
	}
	_, err = netip.ParseAddr(host)
	return err == nil && p.be.NameMatters()
}

// peekHello reads the client's first message, briefly, and returns the
// client's stream with that message still at its front, and the SNI in it.
//
// Read from the connection rather than through br when br holds nothing:
// a bufio.Reader keeps a read's timeout and hands it to the next reader,
// which would end the connection of any protocol where the server speaks
// first. A plain read's timeout is only that.
func (p *LANProxy) peekHello(conn *idleConn, br *bufio.Reader) (io.Reader, string) {
	if n := br.Buffered(); n > 0 {
		b, _ := br.Peek(n)
		return br, sniFrom(b)
	}
	conn.d.Store(int64(lanPeek))
	buf := make([]byte, 4096)
	n, _ := conn.Read(buf)
	conn.d.Store(int64(lanHandshake))
	if n <= 0 {
		return br, ""
	}
	return io.MultiReader(bytes.NewReader(buf[:n]), br), sniFrom(buf[:n])
}

// connectThrough carries one tunnelling request - HTTP CONNECT or SOCKS5's
// CONNECT - from the decision to the end. ok sends the success reply;
// refuse the failure one, forbidden when the target is one a shared device
// may not have.
//
// Ordinarily the far side is dialled first and the client told how that
// went. When the ClientHello has to be read to decide (wantsHello) the
// client has to be told yes first - it sends nothing until it is - so a far
// side that then fails can only be said by closing.
func (p *LANProxy) connectThrough(conn *idleConn, br *bufio.Reader, target, clientIP string,
	ok func() error, refuse func(forbidden bool)) {

	if err := p.checkLANTarget(target); err != nil {
		refuse(true)
		return
	}
	var (
		client io.Reader = br
		sni    string
		early  = p.wantsHello(target)
	)
	if early {
		if ok() != nil {
			return
		}
		client, sni = p.peekHello(conn, br)
	}
	up, spare, row, err := p.dial(target, sni, clientIP)
	if err != nil {
		if !early {
			refuse(false)
		}
		return
	}
	defer p.finish(up, row)
	if !early && ok() != nil {
		return
	}
	if len(spare) > 0 {
		if _, err := conn.Write(spare); err != nil {
			return
		}
		p.be.Hosts().moved(row, 0, int64(len(spare)))
	}
	p.pipe(conn, client, up, row)
}

//------------------------------------------------------------------- HTTP

func (p *LANProxy) authorised(h http.Header) bool {
	if p.password == "" {
		// Start refuses to run without one; this is only never letting an
		// empty one mean "anyone".
		return false
	}
	v := h.Get("Proxy-Authorization")
	const prefix = "Basic "
	if len(v) < len(prefix) || !strings.EqualFold(v[:len(prefix)], prefix) {
		return false
	}
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(v[len(prefix):]))
	if err != nil {
		return false
	}
	user, pass, ok := strings.Cut(string(raw), ":")
	if !ok {
		return false
	}
	// Both compared, both in constant time, and the answer taken from both -
	// so which of the two was wrong is not something the timing tells.
	u := subtle.ConstantTimeCompare([]byte(user), []byte(LANProxyUser))
	w := subtle.ConstantTimeCompare([]byte(pass), []byte(p.password))
	return u&w == 1
}

func httpReply(w io.Writer, status int, extra string) {
	fmt.Fprintf(w, "HTTP/1.1 %d %s\r\n%sContent-Length: 0\r\nConnection: close\r\n\r\n",
		status, http.StatusText(status), extra)
}

// withPort adds a default port to a host that has none.
func withPort(hostport, def string) string {
	if _, _, err := net.SplitHostPort(hostport); err == nil {
		return hostport
	}
	return net.JoinHostPort(strings.Trim(hostport, "[]"), def)
}

func (p *LANProxy) serveHTTP(conn *idleConn, br *bufio.Reader, clientIP string) {
	req, err := http.ReadRequest(br)
	if err != nil {
		return
	}
	if !p.authorised(req.Header) {
		httpReply(conn, http.StatusProxyAuthRequired, "Proxy-Authenticate: Basic realm=\"Mirage\"\r\n")
		return
	}

	if req.Method == http.MethodConnect {
		// Whatever the client sent behind its CONNECT is already in br, and
		// goes on from there.
		p.connectThrough(conn, br, withPort(req.Host, "443"), clientIP,
			func() error {
				_, err := io.WriteString(conn, "HTTP/1.1 200 Connection Established\r\n\r\n")
				return err
			},
			func(forbidden bool) {
				if forbidden {
					httpReply(conn, http.StatusForbidden, "")
				} else {
					httpReply(conn, http.StatusBadGateway, "")
				}
			})
		return
	}

	// A plain request carries its destination in an absolute URI; one that
	// does not was addressed to this proxy as if it were a web server.
	if req.URL == nil || req.URL.Host == "" {
		httpReply(conn, http.StatusBadRequest, "")
		return
	}
	def := "80"
	if req.URL.Scheme == "https" {
		def = "443"
	}
	target := withPort(req.URL.Host, def)
	if err := p.checkLANTarget(target); err != nil {
		httpReply(conn, http.StatusForbidden, "")
		return
	}
	up, spare, row, err := p.dial(target, "", clientIP)
	if err != nil {
		httpReply(conn, http.StatusBadGateway, "")
		return
	}
	hosts := p.be.Hosts()
	defer p.finish(up, row)

	// Origin-form, as a server expects, with what was addressed to the proxy
	// taken off. One request per connection: with Connection: close the
	// answer ends when the server closes, and there is no pipelining to get
	// wrong between the client and a server it never knew it was talking to.
	req.Header.Del("Proxy-Authorization")
	req.Header.Del("Proxy-Connection")
	req.Header.Del("Proxy-Authenticate")
	req.Header.Del("Connection")
	req.RequestURI = ""
	req.Close = true
	conn.d.Store(int64(p.idle))
	if err := req.Write(counted{w: up, h: hosts, row: row}); err != nil {
		httpReply(conn, http.StatusBadGateway, "")
		return
	}
	if len(spare) > 0 {
		if _, err := conn.Write(spare); err != nil {
			return
		}
		hosts.moved(row, 0, int64(len(spare)))
	}
	p.pipe(conn, br, up, row)
}

//------------------------------------------------------------------ SOCKS5

func (p *LANProxy) serveSOCKS(conn *idleConn, br *bufio.Reader, clientIP string) {
	head := make([]byte, 2)
	if _, err := io.ReadFull(br, head); err != nil {
		return
	}
	methods := make([]byte, head[1])
	if _, err := io.ReadFull(br, methods); err != nil {
		return
	}
	// Username/password, always: there is no "no authentication" here.
	const want = byte(0x02)
	if p.password == "" {
		conn.Write([]byte{5, 0xff})
		return
	}
	found := false
	for _, m := range methods {
		if m == want {
			found = true
		}
	}
	if !found {
		conn.Write([]byte{5, 0xff})
		return
	}
	if _, err := conn.Write([]byte{5, want}); err != nil {
		return
	}

	// RFC 1929: ver 1, ulen, user, plen, password.
	ver, err := br.ReadByte()
	if err != nil || ver != 1 {
		return
	}
	ul, err := br.ReadByte()
	if err != nil {
		return
	}
	if _, err := io.ReadFull(br, make([]byte, ul)); err != nil {
		return
	}
	pl, err := br.ReadByte()
	if err != nil {
		return
	}
	pw := make([]byte, pl)
	if _, err := io.ReadFull(br, pw); err != nil {
		return
	}
	if subtle.ConstantTimeCompare(pw, []byte(p.password)) != 1 {
		conn.Write([]byte{1, 1})
		return
	}
	if _, err := conn.Write([]byte{1, 0}); err != nil {
		return
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(br, req); err != nil {
		return
	}
	reply := func(code byte) {
		conn.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	}
	if req[0] != 5 {
		return
	}
	if req[1] != 1 { // CONNECT only
		reply(0x07)
		return
	}
	var host string
	switch req[3] {
	case 1:
		b := make([]byte, 4)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 4:
		b := make([]byte, 16)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		host = net.IP(b).String()
	case 3:
		n, err := br.ReadByte()
		if err != nil || n == 0 {
			return
		}
		b := make([]byte, n)
		if _, err := io.ReadFull(br, b); err != nil {
			return
		}
		// Bytes, as far as SOCKS is concerned - so held to what a name is
		// before it goes anywhere near a CONNECT line (see checkTarget).
		host = strings.ToLower(string(b))
		if !validLANName(strings.TrimSuffix(host, ".")) {
			reply(0x01)
			return
		}
	default:
		reply(0x08)
		return
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(br, pb); err != nil {
		return
	}
	target := net.JoinHostPort(host, strconv.Itoa(int(pb[0])<<8|int(pb[1])))

	p.connectThrough(conn, br, target, clientIP,
		func() error {
			_, err := conn.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
			return err
		},
		func(forbidden bool) {
			if forbidden {
				reply(0x02) // not allowed by ruleset
			} else {
				reply(0x05)
			}
		})
}
