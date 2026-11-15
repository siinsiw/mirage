package core

import (
	"bufio"
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// The tests for the security audit's fixes, one section per finding.

// ------------------------------------------------------------ LAN sharing

func TestLANPasswordIsRequired(t *testing.T) {
	f := newFakeLAN(echo)
	for _, pw := range []string{"", "1234567"} {
		p := NewLANProxy(f, pw)
		p.ifaces = func() []lanIface {
			return []lanIface{{Name: "wlan0", Addrs: []netip.Prefix{netip.MustParsePrefix("192.168.1.5/24")}}}
		}
		if err := p.Start(0); err == nil || !strings.Contains(err.Error(), "password") {
			p.Close()
			t.Fatalf("Start with password %q: %v", pw, err)
		}
		ln, _ := net.Listen("tcp4", "127.0.0.1:0")
		if err := p.Serve(ln); err == nil {
			p.Close()
			t.Fatalf("Serve with password %q started", pw)
		}
		if _, err := net.DialTimeout("tcp", ln.Addr().String(), time.Second); err == nil {
			t.Fatalf("a refused Serve left its listener open")
		}
		p.Close()
	}
	if err := CheckLANPassword(testLANPassword); err != nil {
		t.Fatal(err)
	}
	for pw, ok := range map[string]bool{
		strings.Repeat("x", 128): true, strings.Repeat("x", 129): false,
		"with space ~!": true, "tab\there!": false, "ünïcödé-pw": false, "new\nline-pw": false,
	} {
		if err := CheckLANPassword(pw); (err == nil) != ok {
			t.Errorf("CheckLANPassword(%q) = %v, want ok=%v", pw, err, ok)
		}
	}
}

func TestLANListensOnlyOnShareableInterfaces(t *testing.T) {
	pf := netip.MustParsePrefix
	addrs, nets := lanListenAddrs([]lanIface{
		{Name: "wlan0", Addrs: []netip.Prefix{pf("192.168.1.5/24"), pf("fe80::1/64")}},
		{Name: "rmnet_data0", Addrs: []netip.Prefix{pf("10.33.2.9/30")}}, // cellular, private-looking
		{Name: "ccmni1", Addrs: []netip.Prefix{pf("10.0.0.2/8")}},
		{Name: "seth_lte0", Addrs: []netip.Prefix{pf("10.1.0.2/16")}},
		{Name: "v4-rmnet_data0", Addrs: []netip.Prefix{pf("192.0.0.4/32")}},
		{Name: "ap0", Addrs: []netip.Prefix{pf("192.168.43.1/24")}},
		{Name: "tun0", Addrs: []netip.Prefix{pf("10.111.222.1/24")}},
		{Name: "eth0", Addrs: []netip.Prefix{pf("172.16.5.2/16")}},
		{Name: "wlan1", Addrs: []netip.Prefix{pf("100.64.3.4/10")}}, // CGNAT is not private here
		{Name: "rndis0", Addrs: []netip.Prefix{pf("192.168.42.129/24")}},
		{Name: "mystery0", Addrs: []netip.Prefix{pf("10.9.9.9/24")}}, // unknown: not shared
	})
	var got []string
	for _, a := range addrs {
		got = append(got, a.String())
	}
	want := "172.16.5.2 192.168.1.5 192.168.42.129 192.168.43.1"
	if strings.Join(got, " ") != want {
		t.Fatalf("listens on %v, want %s", got, want)
	}
	if !inPrefixes(nets, netip.MustParseAddr("192.168.1.200")) ||
		!inPrefixes(nets, netip.MustParseAddr("192.168.43.7")) ||
		inPrefixes(nets, netip.MustParseAddr("10.33.2.10")) ||
		inPrefixes(nets, netip.MustParseAddr("192.168.2.1")) {
		t.Fatalf("subnets wrong: %v", nets)
	}
	for name, want := range map[string]bool{
		"wlan0": true, "swlan0": true, "ap0": true, "eth0": true, "rndis0": true, "bt-pan": true,
		"rmnet0": false, "rmnet_ipa0": false, "ccmni0": false, "pdp0": false, "wwan0": false,
		"ppp0": false, "clat4": false, "tun0": false, "lo": false, "dummy0": false, "": false,
	} {
		if shareable(name) != want {
			t.Errorf("shareable(%q) != %v", name, want)
		}
	}
}

func TestLANSourceMustBeInAnInterfaceSubnet(t *testing.T) {
	p := NewLANProxy(newFakeLAN(echo), testLANPassword)
	p.ifaces = func() []lanIface {
		return []lanIface{
			{Name: "wlan0", Addrs: []netip.Prefix{netip.MustParsePrefix("192.168.1.5/24")}},
			{Name: "rmnet0", Addrs: []netip.Prefix{netip.MustParsePrefix("10.33.2.9/24")}},
		}
	}
	for ip, want := range map[string]bool{
		"192.168.1.77": true,
		"192.168.2.5":  false, // private, but not this Wi-Fi
		"10.33.2.10":   false, // the carrier's network
		"100.64.0.5":   false,
		"169.254.1.1":  false,
		"8.8.8.8":      false,
	} {
		if got := p.allow(net.ParseIP(ip)); got != want {
			t.Errorf("source %s allowed=%v, want %v", ip, got, want)
		}
	}
}

// serveLAN is startLAN with the phone's own addresses given.
func serveLAN(t *testing.T, f *fakeLAN, own ...string) string {
	t.Helper()
	p := NewLANProxy(f, testLANPassword)
	p.allow = anyone
	p.own = func() []netip.Addr {
		var out []netip.Addr
		for _, s := range own {
			out = append(out, netip.MustParseAddr(s))
		}
		return out
	}
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	if err := p.Serve(ln); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { p.Close() })
	return ln.Addr().String()
}

func TestLANRefusesTheseTargets(t *testing.T) {
	for _, straight := range []bool{false, true} {
		f := newFakeLAN(echo)
		f.straight = straight
		addr := serveLAN(t, f, "192.168.1.5")
		for _, target := range []string{
			"127.0.0.1:80", "127.1.2.3:18080", "[::1]:443", "169.254.169.254:80", "0.0.0.0:80",
			"0.1.2.3:80", "192.168.1.5:22", "localhost:80", "api.localhost:443", "224.0.0.1:80",
			"[fe80::1]:80", "255.255.255.255:9",
		} {
			c := dialLAN(t, addr)
			io.WriteString(c, "CONNECT "+target+" HTTP/1.1\r\nHost: "+target+"\r\n"+lanAuth()+"\r\n")
			resp, err := http.ReadResponse(bufio.NewReader(c), nil)
			if err != nil || resp.StatusCode != http.StatusForbidden {
				t.Errorf("straight=%v CONNECT %s: %v %v", straight, target, resp, err)
			}
		}
		// A plain request, and SOCKS5 by address.
		c := dialLAN(t, addr)
		io.WriteString(c, "GET http://127.0.0.1:8080/ HTTP/1.1\r\nHost: 127.0.0.1:8080\r\n"+lanAuth()+"\r\n")
		if resp, err := http.ReadResponse(bufio.NewReader(c), nil); err != nil || resp.StatusCode != http.StatusForbidden {
			t.Errorf("GET to loopback: %v %v", resp, err)
		}
		c = dialLAN(t, addr)
		socksLogin(t, c)
		c.Write([]byte{5, 1, 0, 1, 127, 0, 0, 1, 0, 80})
		rep := make([]byte, 10)
		if _, err := io.ReadFull(c, rep); err != nil || rep[1] != 0x02 {
			t.Errorf("SOCKS to loopback: %v %v", rep, err)
		}
		select {
		case tg := <-f.targets:
			t.Fatalf("a refused target was dialled: %s", tg)
		default:
		}
	}

	// And the guard on the straight line's actual dial, after names resolve.
	for addr, refused := range map[string]bool{
		"127.0.0.1:80": true, "[::1]:80": true, "169.254.1.1:80": true, "0.0.0.0:80": true,
		"8.8.8.8:53": false, "93.184.216.34:443": false,
	} {
		if err := lanDialGuard("tcp4", addr, nil); (err != nil) != refused {
			t.Errorf("lanDialGuard(%s) = %v, refused should be %v", addr, err, refused)
		}
	}
}

func TestLANSOCKSRefusesABadName(t *testing.T) {
	f := newFakeLAN(echo)
	addr := serveLAN(t, f)
	for _, name := range []string{"evil.com\r\nX-Injected: 1", "a b.com", "a\x00.com", strings.Repeat("a", 64) + ".com"} {
		c := dialLAN(t, addr)
		socksLogin(t, c)
		req := append([]byte{5, 1, 0, 3, byte(len(name))}, name...)
		c.Write(append(req, 0x01, 0xbb))
		rep := make([]byte, 10)
		if _, err := io.ReadFull(c, rep); err != nil || rep[1] == 0 {
			t.Errorf("SOCKS name %q: %v %v", name, rep, err)
		}
	}
	select {
	case tg := <-f.targets:
		t.Fatalf("a bad name was dialled: %q", tg)
	default:
	}
}

func TestCheckTarget(t *testing.T) {
	for target, ok := range map[string]bool{
		"example.com:443":                true,
		"93.184.216.34:443":              true,
		"[2001:db8::1]:443":              true,
		"example.com\r\nX-Evil: 1:443":   false,
		"example.com:443\r\nX-Evil: 1":   false,
		"exa mple.com:443":               false,
		"example.com:0":                  false,
		"example.com:65536":              false,
		"example.com:http":               false,
		"example.com:+443":               false,
		"example.com":                    false,
		":443":                           false,
		"\x7fexample.com:443":            false,
		strings.Repeat("a", 254) + ":80": false,
	} {
		if err := checkTarget(target); (err == nil) != ok {
			t.Errorf("checkTarget(%q) = %v, want ok=%v", target, err, ok)
		}
	}

	// And the server's CONNECT refuses one before writing a byte.
	var rec recordConn
	srv := NewTunnelServer("mirage.example.com", "pw", nil, ModeSingle)
	if _, err := srv.connect(&rec, "a.com\r\nHost: b:443"); err == nil || rec.Len() != 0 {
		t.Fatalf("a CRLF target was written: %v %q", err, rec.String())
	}
}

// recordConn is a net.Conn that keeps what is written to it.
type recordConn struct {
	net.Conn
	bytes.Buffer
}

func (r *recordConn) Write(b []byte) (int, error) { return r.Buffer.Write(b) }
func (r *recordConn) Read([]byte) (int, error)    { return 0, io.EOF }

// flakyListener fails its first few Accepts the way a process short of
// descriptors does, then hands out what it is given.
type flakyListener struct {
	errs   atomic.Int32
	conns  chan net.Conn
	closed chan struct{}
	once   sync.Once
}

func (l *flakyListener) Accept() (net.Conn, error) {
	if l.errs.Add(-1) >= 0 {
		return nil, errors.New("accept4: too many open files")
	}
	select {
	case c := <-l.conns:
		return c, nil
	case <-l.closed:
		return nil, net.ErrClosed
	}
}
func (l *flakyListener) Close() error   { l.once.Do(func() { close(l.closed) }); return nil }
func (l *flakyListener) Addr() net.Addr { return &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9} }

// lanPeer is one end of a pipe that says it came from the LAN.
type lanPeer struct{ net.Conn }

func (lanPeer) RemoteAddr() net.Addr {
	return &net.TCPAddr{IP: net.IPv4(192, 168, 1, 20), Port: 5555}
}

func TestLANAcceptSurvivesTemporaryErrors(t *testing.T) {
	f := newFakeLAN(echo)
	p := NewLANProxy(f, testLANPassword)
	p.allow = anyone
	ln := &flakyListener{conns: make(chan net.Conn, 1), closed: make(chan struct{})}
	ln.errs.Store(4)
	if err := p.Serve(ln); err != nil {
		t.Fatal(err)
	}
	defer p.Close()

	client, server := net.Pipe()
	defer client.Close()
	ln.conns <- lanPeer{server}
	client.SetDeadline(time.Now().Add(5 * time.Second))
	io.WriteString(client, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n"+lanAuth()+"\r\n")
	br := bufio.NewReader(client)
	if resp, err := http.ReadResponse(br, nil); err != nil || resp.StatusCode != 200 {
		t.Fatalf("after accept errors: %v %v", resp, err)
	}
	if !p.Running() {
		t.Fatal("not running after recovering")
	}
	p.Close()
	if p.Running() {
		t.Fatal("running after Close")
	}
}

// An address target with names listed: the proxy says yes first, reads the
// ClientHello, and the SNI in it is what Straight is asked about.
func TestLANDecidesByTheClientHello(t *testing.T) {
	f := newFakeLAN(echo)
	f.names = true
	addr := serveLAN(t, f)
	// Made first: the proxy waits lanPeek for it, and making one is not
	// free on a busy machine.
	hello := clientHello(t, "www.example.com")
	connect := "CONNECT 93.184.216.34:443 HTTP/1.1\r\nHost: 93.184.216.34:443\r\n" + lanAuth() + "\r\n"

	// Sent after the 200, as a browser does - and sent behind the CONNECT in
	// the same write, which arrives already buffered.
	for _, pipelined := range []bool{false, true} {
		c := dialLAN(t, addr)
		br := bufio.NewReader(c)
		if pipelined {
			c.Write(append([]byte(connect), hello...))
		} else {
			io.WriteString(c, connect)
		}
		if resp, err := http.ReadResponse(br, nil); err != nil || resp.StatusCode != 200 {
			t.Fatalf("CONNECT: %v %v", resp, err)
		}
		if !pipelined {
			c.Write(hello)
		}
		back := make([]byte, len(hello))
		if _, err := io.ReadFull(br, back); err != nil || !bytes.Equal(back, hello) {
			t.Fatalf("pipelined=%v: the ClientHello did not arrive intact: %v", pipelined, err)
		}
		if got := <-f.asked; got != [2]string{"93.184.216.34", "www.example.com"} {
			t.Fatalf("pipelined=%v: Straight was asked %q", pipelined, got)
		}
	}

	// A protocol where the server speaks first: nothing comes, the wait is
	// short, and the connection is none the worse for it.
	c := dialLAN(t, addr)
	br := bufio.NewReader(c)
	io.WriteString(c, "CONNECT 93.184.216.34:22 HTTP/1.1\r\nHost: 93.184.216.34:22\r\n"+lanAuth()+"\r\n")
	if resp, err := http.ReadResponse(br, nil); err != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT :22: %v %v", resp, err)
	}
	if got := <-f.asked; got != [2]string{"93.184.216.34", ""} {
		t.Fatalf("Straight was asked %q", got)
	}
	io.WriteString(c, "ping")
	buf := make([]byte, 4)
	if _, err := io.ReadFull(br, buf); err != nil || string(buf) != "ping" {
		t.Fatalf("after the empty peek: %q %v", buf, err)
	}

	// A name is decided by the name: nothing is read first.
	c = dialLAN(t, addr)
	io.WriteString(c, "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n"+lanAuth()+"\r\n")
	if resp, err := http.ReadResponse(bufio.NewReader(c), nil); err != nil || resp.StatusCode != 200 {
		t.Fatalf("CONNECT by name: %v %v", resp, err)
	}
	if got := <-f.asked; got != [2]string{"example.com", ""} {
		t.Fatalf("Straight was asked %q", got)
	}
}

// ------------------------------------------------------- server API pinning

// selfSigned is a certificate for 127.0.0.1 and its pin.
func selfSigned(t *testing.T, serial int64) (tls.Certificate, string) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := x509.Certificate{
		SerialNumber: big.NewInt(serial),
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		IPAddresses:  []net.IP{net.IPv4(127, 0, 0, 1)},
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, &tmpl, &tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(der)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, hex.EncodeToString(sum[:])
}

func TestServerAPIRefusesAnAddressWithoutAPin(t *testing.T) {
	if _, err := ServerAPI("127.0.0.1", 0, "apw", "", http.MethodGet, "config", nil, time.Second); !errors.Is(err, ErrNoPin) {
		t.Fatalf("no pin: %v", err)
	}
	hop := ExitHop{IP: "146.70.194.221", Hostname: "de-fra.prod.surfshark.com", User: "u", Password: "p", Verify: true}
	if err := SetExit("203.0.113.7", 0, "apw", " ", hop, time.Second); !errors.Is(err, ErrNoPin) {
		t.Fatalf("SetExit with no pin: %v", err)
	}
}

func TestServerAPIPinnedAndReused(t *testing.T) {
	var bodies []string
	var mu sync.Mutex
	ts := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if u, p, ok := r.BasicAuth(); !ok || u != ServerUser || p != "apw" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		b, _ := io.ReadAll(r.Body)
		mu.Lock()
		bodies = append(bodies, r.Method+" "+r.URL.Path+" "+string(b))
		mu.Unlock()
		io.WriteString(w, `{"data":{"name":"exit-chain"}}`)
	}))
	var conns atomic.Int32
	ts.Config.ConnState = func(_ net.Conn, s http.ConnState) {
		if s == http.StateNew {
			conns.Add(1)
		}
	}
	ts.StartTLS()
	defer ts.Close()
	_, portS, _ := net.SplitHostPort(ts.Listener.Addr().String())
	port, _ := strconv.Atoi(portS)
	sum := sha256.Sum256(ts.Certificate().Raw)
	pin := hex.EncodeToString(sum[:])

	if out, err := ChainJSON("127.0.0.1", port, "apw", pin, "exit-chain", 5*time.Second); err != nil || !strings.Contains(out, "exit-chain") {
		t.Fatalf("ChainJSON: %q %v", out, err)
	}
	hop := ExitHop{IP: "146.70.194.221", Hostname: "de-fra.prod.surfshark.com", User: "u", Password: "p", Node: "x.ovpn", Verify: true}
	if err := SetExit("127.0.0.1", port, "apw", strings.ToUpper(pin), hop, 5*time.Second); err != nil {
		t.Fatalf("SetExit: %v", err)
	}
	if err := SetExit("127.0.0.1", port, "apw", pin, hop, 5*time.Second); err != nil {
		t.Fatalf("SetExit again: %v", err)
	}
	if n := conns.Load(); n != 1 {
		// The pin's case does not make it another server: one transport,
		// one kept-alive connection. Three would be a transport per call.
		t.Fatalf("%d connections for three requests", n)
	}
	if apiTransport("127.0.0.1", port, pin) != apiTransport("127.0.0.1", port, pin) {
		t.Fatal("a second transport for the same server and pin")
	}
	mu.Lock()
	if len(bodies) != 3 || !strings.Contains(bodies[1], `"serverName":"de-fra.prod.surfshark.com"`) ||
		!strings.Contains(bodies[1], `"secure":true`) || !strings.HasPrefix(bodies[1], "PUT /api/config/chains/exit-chain") {
		t.Fatalf("requests: %q", bodies)
	}
	mu.Unlock()

	if _, err := ChainJSON("127.0.0.1", port, "apw", strings.Repeat("0", 64), "exit-chain", 5*time.Second); err == nil ||
		!strings.Contains(err.Error(), ErrPinChanged.Error()) {
		t.Fatalf("a wrong pin was not refused: %v", err)
	}
}

// pinServer is a TLS server by address that records, for every HTTP request
// that reaches it, the pin the client held at that moment. With switching,
// every handshake after the first shows a different certificate.
func pinServer(t *testing.T, srv **TunnelServer, switching bool) (port int, pinA string, handshakes *atomic.Int32, requests chan string) {
	t.Helper()
	certA, pinA := selfSigned(t, 1)
	certB, _ := selfSigned(t, 2)
	handshakes = &atomic.Int32{}
	cfg := &tls.Config{GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
		if handshakes.Add(1) > 1 && switching {
			return &certB, nil
		}
		return &certA, nil
	}}
	ln, err := tls.Listen("tcp", "127.0.0.1:0", cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	requests = make(chan string, 4)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				c.SetDeadline(time.Now().Add(5 * time.Second))
				req, err := http.ReadRequest(bufio.NewReader(c))
				if err != nil {
					return
				}
				requests <- (*srv).pin() + " " + req.URL.Path
				io.WriteString(c, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n")
			}(c)
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, pinA, handshakes, requests
}

// A server by address with no pin is not dialled: no handshake, no request,
// and so no password to whatever happens to answer there first.
func TestUnpinnedAddressIsRefusedBeforeAnythingIsSent(t *testing.T) {
	var srv *TunnelServer
	port, _, handshakes, requests := pinServer(t, &srv, false)
	srv = NewTunnelServer("127.0.0.1", "tunnel-pw", nil, ModeBulk)
	srv.Port = port
	defer srv.Close()

	_, _, err := srv.Open("example.com:443", 5*time.Second)
	if err == nil || !strings.Contains(err.Error(), ErrNoPin.Error()) {
		t.Fatalf("an address without a pin was dialled: %v", err)
	}
	select {
	case got := <-requests:
		t.Fatalf("a request reached a server nobody had confirmed: %s", got)
	case <-time.After(300 * time.Millisecond):
	}
	if n := handshakes.Load(); n != 0 {
		t.Fatalf("%d handshakes with a server that has no pin", n)
	}
}

// PeekPin shows the certificate and trusts nothing: one bare handshake, no
// request, and the server is still refused until the pin is handed back.
func TestPeekPinShowsWithoutTrusting(t *testing.T) {
	var srv *TunnelServer
	port, pinA, handshakes, requests := pinServer(t, &srv, false)
	srv = NewTunnelServer("127.0.0.1", "tunnel-pw", nil, ModeBulk)
	srv.Port = port
	defer srv.Close()

	seen, err := srv.PeekPin(context.Background(), 5*time.Second)
	if err != nil || seen != pinA {
		t.Fatalf("PeekPin = %q, %v; want %s", seen, err, pinA)
	}
	if n := handshakes.Load(); n != 1 {
		t.Fatalf("%d handshakes for one look", n)
	}
	if srv.pin() != "" {
		t.Fatalf("looking kept the pin %q", srv.pin())
	}
	if _, _, err := srv.Open("example.com:443", 5*time.Second); err == nil ||
		!strings.Contains(err.Error(), ErrNoPin.Error()) {
		t.Fatalf("a look was taken for a confirmation: %v", err)
	}
	select {
	case got := <-requests:
		t.Fatalf("a request was sent before the pin was confirmed: %s", got)
	case <-time.After(300 * time.Millisecond):
	}

	// Confirmed: the pin is handed back, and only now does anything travel.
	srv.Pin = seen
	_, _, err = srv.Open("example.com:443", 5*time.Second)
	if err == nil || !strings.Contains(err.Error(), "404") {
		t.Fatalf("expected the 404 this server gives: %v", err)
	}
	select {
	case got := <-requests:
		if got != pinA+" /gwb" {
			t.Fatalf("the request arrived with the pin %q, want %s held", got, pinA)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("no request arrived")
	}
	if srv.SeenPin() != pinA {
		t.Fatalf("SeenPin %q", srv.SeenPin())
	}
}

// The certificate that was confirmed is the one the authenticating
// connection has to show: another one after the look is refused unsent.
func TestConfirmedPinHoldsTheConnectionThatAuthenticates(t *testing.T) {
	var srv *TunnelServer
	port, pinA, _, requests := pinServer(t, &srv, true)
	srv = NewTunnelServer("127.0.0.1", "tunnel-pw", nil, ModeSingle)
	srv.Port = port
	defer srv.Close()

	seen, err := srv.PeekPin(context.Background(), 5*time.Second)
	if err != nil || seen != pinA {
		t.Fatalf("PeekPin = %q, %v; want %s", seen, err, pinA)
	}
	srv.Pin = seen

	_, _, err = srv.Open("example.com:443", 5*time.Second)
	if err == nil || !strings.Contains(err.Error(), ErrPinChanged.Error()) {
		t.Fatalf("a certificate that changed after it was confirmed was not refused: %v", err)
	}
	select {
	case got := <-requests:
		t.Fatalf("a request reached a server showing another certificate: %s", got)
	case <-time.After(300 * time.Millisecond):
	}
}

// A server by name has no pin to show: a CA vouches for it.
func TestPeekPinIsNothingForAName(t *testing.T) {
	srv := NewTunnelServer("example.invalid", "tunnel-pw", nil, ModeSingle)
	defer srv.Close()
	if seen, err := srv.PeekPin(context.Background(), time.Second); seen != "" || err != nil {
		t.Fatalf("PeekPin of a name = %q, %v", seen, err)
	}
}

// ------------------------------------------------------ provider TLS check

func TestChainBodyVerify(t *testing.T) {
	type dialer struct {
		Type string         `json:"type"`
		TLS  map[string]any `json:"tls"`
	}
	read := func(b []byte) (node map[string]any, d dialer) {
		var c struct {
			Name string `json:"name"`
			Hops []struct {
				Nodes []map[string]any `json:"nodes"`
			} `json:"hops"`
		}
		if err := json.Unmarshal(b, &c); err != nil || c.Name != "exit-chain" || len(c.Hops) != 1 || len(c.Hops[0].Nodes) != 1 {
			t.Fatalf("chain: %s %v", b, err)
		}
		node = c.Hops[0].Nodes[0]
		raw, _ := json.Marshal(node["dialer"])
		json.Unmarshal(raw, &d)
		return node, d
	}

	hop := ExitHop{IP: "146.70.194.221", Hostname: "de-fra.prod.surfshark.com", User: "u", Password: "p", Verify: true}
	b, err := chainBody("exit-chain", hop)
	if err != nil {
		t.Fatal(err)
	}
	node, d := read(b)
	if d.Type != "tls" || d.TLS["secure"] != true || d.TLS["serverName"] != "de-fra.prod.surfshark.com" {
		t.Fatalf("verify on: %s", b)
	}
	if node["addr"] != "146.70.194.221:443" || node["name"] != "current" {
		t.Fatalf("node: %v", node)
	}

	hop.Verify = false
	b, _ = chainBody("exit-chain", hop)
	if _, d = read(b); d.TLS["secure"] != false || d.TLS["serverName"] != nil {
		t.Fatalf("verify off: %s", b)
	}

	for _, name := range []string{"", "146.70.194.221", "bad name"} {
		if _, err := chainBody("exit-chain", ExitHop{IP: "146.70.194.221", Hostname: name, Verify: true}); err == nil {
			t.Errorf("verify with hostname %q was accepted", name)
		}
	}
}

func TestServerInIran(t *testing.T) {
	ctx := context.Background()
	for host, want := range map[string]bool{
		"2.144.0.1":      true, // from lists/iran-ipv4.txt
		"5.200.200.200":  true,
		"8.8.8.8":        false,
		"146.70.194.221": false,
		"2001:db8::1":    false,
	} {
		got, err := ServerInIran(ctx, host)
		if err != nil || got != want {
			t.Errorf("ServerInIran(%s) = %v %v, want %v", host, got, err, want)
		}
	}
	if _, err := ServerInIran(ctx, "not a host"); err == nil {
		t.Error("a host that is neither an address nor a name was answered")
	}
}

// ------------------------------------------------- scripts in pinned configs

func TestPinnedConfigsCarryNoScripts(t *testing.T) {
	in := strings.Join([]string{
		"client",
		"remote de-fra.prod.surfshark.com 1443",
		"up /etc/openvpn/up.sh",
		"  Script-Security 2",
		"--route-up \"/bin/sh -c 'id'\"",
		"setenv FOO bar",
		"SETENV-SAFE X y",
		"plugin /usr/lib/evil.so",
		"tls-verify /bin/true",
		"auth-user-pass-verify /bin/true via-env",
		"down-pre",
		"upper-case-is-not-up 1",
		"<ca>",
		"up this line is certificate text and stays",
		"config this one too",
		"</ca>",
		"<tls-auth>",
		"management 127.0.0.1 7505 inside a block stays",
		"</tls-auth>",
		"auth-user-pass",
		"CONFIG /etc/other.conf",
		"--cd /tmp",
		"chroot /var/empty",
		"log /tmp/x.log",
		"Log-Append /tmp/y.log",
		"status /tmp/status 10",
		"writepid /tmp/pid",
		"tmp-dir /tmp",
		"management 0.0.0.0 7505",
		"replay-persist /tmp/replay",
		"iproute /bin/evil",
		"daemon",
		"askpass /tmp/pass.txt",
		"verb 3",
		"",
	}, "\n")
	out := pinnedText(in, "de-fra.prod.surfshark.com", "146.70.194.221")
	if !strings.HasPrefix(out, "# pinned by Mirage on ") {
		t.Fatalf("the pinning header is not first:\n%s", out)
	}
	for _, gone := range []string{"up.sh", "Script-Security", "route-up", "setenv FOO", "SETENV-SAFE",
		"evil.so", "tls-verify /bin", "auth-user-pass-verify", "\ndown-pre", "/etc/other.conf",
		"--cd", "chroot /var", "x.log", "y.log", "/tmp/status", "/tmp/pid", "tmp-dir /tmp",
		"0.0.0.0 7505", "/tmp/replay", "/bin/evil", "\ndaemon", "pass.txt"} {
		// Looked for in the config itself, not in the note naming what went.
		body := out[strings.Index(out, "\nclient\n"):]
		if strings.Contains(body, gone) {
			t.Errorf("%q survived:\n%s", gone, out)
		}
	}
	for _, kept := range []string{"remote 146.70.194.221 1443", "upper-case-is-not-up 1",
		"up this line is certificate text and stays", "config this one too",
		"management 127.0.0.1 7505 inside a block stays", "auth-user-pass\n", "verb 3",
		"# removed by Mirage: up, script-security"} {
		if !strings.Contains(out, kept) {
			t.Errorf("%q is missing:\n%s", kept, out)
		}
	}
	// Nothing to remove: the text is as it was.
	if clean := "client\nremote 1.2.3.4 443\n"; stripScripts(clean) != clean {
		t.Fatal("a clean config was changed")
	}
}

// The ways round a look at the first word, each of which OpenVPN reads as
// the option it spells.
func TestStripScriptsBypasses(t *testing.T) {
	for name, cfg := range map[string]string{
		"double-quoted":           "\"up\" /tmp/evil.sh",
		"single-quoted":           "'plugin' /tmp/evil.so",
		"quote then more":         "\"up\"x /tmp/evil.sh",
		"backslash":               "u\\p /tmp/evil.sh",
		"backslash first":         "\\plugin /tmp/evil.so",
		"dashes":                  "--up /tmp/evil.sh",
		"quoted with dashes":      "\"--setenv\" X /tmp/evil",
		"case":                    "  PlUgIn /tmp/evil.so",
		"tab":                     "\tup\t/tmp/evil.sh",
		"crlf":                    "up /tmp/evil.sh\r",
		"block closed by prefix":  "<ca>\nMIIB\n</ca> trailing words\nup /tmp/evil.sh",
		"block closed at once":    "<ca>\n</ca>\nup /tmp/evil.sh",
		"not a tag: two tokens":   "<a b>\nup /tmp/evil.sh\n</a b>",
		"not a data tag":          "<foo>\nup /tmp/evil.sh\n</foo>",
		"tag case matters":        "<CA>\nup /tmp/evil.sh\n</CA>",
		"tag with more on line":   "<ca> x\nup /tmp/evil.sh\n</ca>",
		"inside connection":       "<connection>\nremote 1.2.3.4 443\nup /tmp/evil.sh\n</connection>",
		"script-security 1 alone": "script-security 1\nplugin /tmp/evil.so",
	} {
		out := stripScripts("client\n" + cfg + "\nverb 3\n")
		body := out[strings.Index(out, "client\n"):]
		if strings.Contains(body, "/tmp/evil") {
			t.Errorf("%s: survived:\n%s", name, out)
		}
		if !strings.Contains(body, "verb 3") {
			t.Errorf("%s: the rest of the config went too:\n%s", name, out)
		}
	}

	// And what OpenVPN reads as data or as nothing is kept.
	for name, cfg := range map[string]string{
		"comment #":        "#up /tmp/kept.sh",
		"comment ;":        "  ; plugin /tmp/kept.so",
		"inside ca":        "<ca>\nup /tmp/kept.sh\n</ca>",
		"inside quoted ca": "\"<ca>\"\ndown /tmp/kept.sh\n</ca>",
		"inside tls-auth":  "<tls-auth>\nplugin /tmp/kept.so\n</tls-auth>",
		"not the option":   "upx /tmp/kept.sh",
	} {
		if out := stripScripts("client\n" + cfg + "\n"); !strings.Contains(out, "/tmp/kept") {
			t.Errorf("%s: removed, but OpenVPN would not run it:\n%s", name, out)
		}
	}
	// A byte-order mark, which OpenVPN skips, in front of the first line.
	for _, out := range []string{
		stripScripts(utf8BOM + "up /tmp/evil.sh\nverb 3\n"),
		stripScripts(utf8BOM + "--plugin /tmp/evil.so\nverb 3\n"),
		pinnedText(utf8BOM+"up /tmp/evil.sh\nremote a.example.com 443\nverb 3\n", "a.example.com", "1.2.3.4"),
	} {
		if strings.Contains(out, "/tmp/evil") || strings.Contains(out, utf8BOM) || !strings.Contains(out, "verb 3") {
			t.Errorf("BOM: %q", out)
		}
	}

	// <connection> itself stays, with its options.
	out := stripScripts("<connection>\nremote 1.2.3.4 443\nup /x\n</connection>\n")
	if !strings.Contains(out, "<connection>\nremote 1.2.3.4 443\n</connection>") {
		t.Errorf("connection block mangled:\n%s", out)
	}
	if got := ovpnTokens(`  "a b" 'c\d' e\ f "g\"h" # i`); strings.Join(got, "|") != `a b|c\d|e f|g"h|#|i` {
		t.Errorf("ovpnTokens: %q", got)
	}
}

// ------------------------------------------------------- split by the SNI

func TestSplitDecidesBySNIFirst(t *testing.T) {
	s, err := NewSplit(SplitBypass, false, []string{"digikala.com"}, nil, "")
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		sni, remembered string
		want            bool
	}{
		{"www.digikala.com", "google.com", true}, // the SNI wins over a wrong guess
		{"google.com", "digikala.com", false},    // and the other way round
		{"", "digikala.com", true},               // no SNI: the remembered name
		{"", "", false},
	} {
		if got := s.ListedByName(c.sni, c.remembered); got != c.want {
			t.Errorf("ListedByName(%q, %q) = %v", c.sni, c.remembered, got)
		}
	}
}

// ------------------------------------------- no session after StopTunnel

func TestClosedServerDoesNotDialAgain(t *testing.T) {
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 8)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			accepted <- c // held open, never answering TLS
		}
	}()
	defer func() {
		for len(accepted) > 0 {
			(<-accepted).Close()
		}
	}()
	port := ln.Addr().(*net.TCPAddr).Port

	srv := NewTunnelServer("127.0.0.1", "pw", nil, ModeSingle)
	srv.Port = port
	srv.Pin = strings.Repeat("a", 64)

	// A dial in flight is cut short by Close rather than waited out.
	done := make(chan error, 1)
	go func() {
		_, _, err := srv.Open("example.com:443", 20*time.Second)
		done <- err
	}()
	select {
	case c := <-accepted:
		defer c.Close()
	case <-time.After(5 * time.Second):
		t.Fatal("the server was never dialled")
	}
	start := time.Now()
	srv.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("a dial cut short by Close succeeded")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Close did not end the dial in flight")
	}
	if d := time.Since(start); d > 3*time.Second {
		t.Fatalf("Close took %v", d)
	}

	// After Close: refused, and nothing dialled.
	if _, _, err := srv.Open("example.com:443", time.Second); !errors.Is(err, ErrServerClosed) {
		t.Fatalf("after Close: %v", err)
	}
	select {
	case c := <-accepted:
		c.Close()
		t.Fatal("a closed server dialled again")
	case <-time.After(300 * time.Millisecond):
	}

	// Reset is not Close: the server stays usable.
	other := NewTunnelServer("127.0.0.1", "pw", nil, ModeSingle)
	other.Port = port
	other.Pin = strings.Repeat("a", 64)
	other.Reset()
	if _, _, err := other.Open("example.com:443", 300*time.Millisecond); errors.Is(err, ErrServerClosed) {
		t.Fatal("Reset closed the server for good")
	}
	other.Close()
}

// ------------------------------------------------------ mirage key rules

func TestMirageKeySharedRules(t *testing.T) {
	base := func(host, pw, apw, ss string) string {
		j := `{"v":1,"host":"` + host + `","pw":` + strconv.Quote(pw) + `,"apw":` + strconv.Quote(apw)
		if ss != "" {
			j += `,"ss":{"method":"aes-256-gcm","password":` + strconv.Quote(ss) + `,"port":8388,"exitPort":8389}`
		}
		return key(j + "}")
	}
	alnum := "Ab3dE6gH9jK2mN5pQ8sT" // what install-server.sh makes: 20 letters and digits
	if _, err := ParseMirageKey(base("mirage.example.com", alnum, alnum, alnum)); err != nil {
		t.Fatalf("an installer key was refused: %v", err)
	}
	if _, err := ParseMirageKey(base("203.0.113.7", "p!%&()*+,-./:;<=>?@[]^_|~", strings.Repeat("x", 128), "")); err != nil {
		t.Fatalf("allowed punctuation and 128 characters were refused: %v", err)
	}
	for _, bad := range []string{"a'b", `a"b`, "a`b", "a$b", `a\b`, "a{b", "a}b", "a#b", "a b", "a\tb", "é",
		strings.Repeat("x", 129)} {
		for i, k := range []string{
			base("mirage.example.com", bad, alnum, ""),
			base("mirage.example.com", alnum, bad, ""),
			base("mirage.example.com", alnum, alnum, bad),
		} {
			if _, err := ParseMirageKey(k); err == nil {
				t.Errorf("password %q in field %d was accepted", bad, i)
			}
		}
	}
	for host, ok := range map[string]bool{
		"mirage.example.com": true, "203.0.113.7": true, "localhost": true, "xn--mgba3a4f16a.ir": true,
		"-bad.example.com": false, "bad-.example.com": false, "a..b": false, "a_b.com": false,
		"::1": false, "2001:db8::1": false, "a.com.": false, strings.Repeat("a", 64) + ".com": false,
		strings.Repeat("a.", 127) + "aa": false,
	} {
		if validHost(host) != ok {
			t.Errorf("validHost(%q) != %v", host, ok)
		}
	}
}
