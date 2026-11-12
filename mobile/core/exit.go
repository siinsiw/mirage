package core

import (
	"bytes"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// ProxyPort is where these exits run their HTTPS proxy. The same machines
// answer OpenVPN on 1443; this is the port the line cannot tell from any
// other TLS connection.
const ProxyPort = 443

var headEnd = []byte("\r\n\r\n")

// controlFunc is what net.Dialer.Control takes.
type controlFunc func(network, address string, c syscall.RawConn) error

// Set on Android so the connection to the exit is kept out of the tun this
// process is serving. Nil everywhere else. See protect.go for why it exists
// at all.
//
// Held atomically rather than as a plain variable: the platform sets it from
// its own thread while dials are already running on others, and a func value
// read and written at once is a data race - the same reason naming.go keeps
// its lookups behind a lock.
var dialProtect atomic.Pointer[controlFunc]

// dialControl is what every dial that must stay out of the tun hands to
// net.Dialer.Control. With no protector set it lets the socket be, which is
// an ordinary dial.
func dialControl(network, address string, c syscall.RawConn) error {
	if f := dialProtect.Load(); f != nil {
		return (*f)(network, address, c)
	}
	return nil
}

// checkTarget is whether target ("host:port") can be written into a CONNECT
// line as it is. The line is text, and a host carrying a CR or LF would end
// it early and let whoever chose the name write the rest of the request -
// headers, a second request - to the exit or the user's own server, under
// this app's credential. So: a host and a numeric port, nothing outside
// printable ASCII, no spaces, and no longer than a name can be.
func checkTarget(target string) error {
	if len(target) == 0 || len(target) > 253+8 {
		return errors.New("that destination is not one a CONNECT can carry")
	}
	for i := 0; i < len(target); i++ {
		if c := target[i]; c <= 0x20 || c >= 0x7f {
			return errors.New("that destination has characters a CONNECT cannot carry")
		}
	}
	host, port, err := net.SplitHostPort(target)
	if err != nil || host == "" || len(host) > 253 {
		return errors.New("that destination is not a host and a port")
	}
	if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 || strings.Trim(port, "0123456789") != "" {
		return errors.New("that destination's port is not a port")
	}
	return nil
}

// Exit is one server, ready to be dialled.
type Exit struct {
	Addr string // pinned IPv4
	Name string // what the certificate must serve
	Port int
	auth string // base64 of user:password, precomputed
}

func NewExit(addr, name, user, password string) *Exit {
	return &Exit{
		Addr: addr,
		Name: name,
		Port: ProxyPort,
		auth: base64.StdEncoding.EncodeToString([]byte(user + ":" + password)),
	}
}

// ErrFiltered is the third outcome, and worth telling apart from the other
// two. TCP completes at the normal round trip and then the TLS handshake is
// answered by nobody - the server is not down, because reached through
// another exit the same address completes TLS immediately. Something between
// here and it takes the SYN and then swallows the payload, so this address is
// filtered on this line and no amount of retrying will change it. Re-pin the
// exit and take a different address for it.
var ErrFiltered = errors.New("filtered here - TCP answers, TLS gets nothing back")

// ErrTargetRefused is the way out answering, and saying no about where it was
// asked to go - a 502 for a site that is down, a 403 for a port it will not
// carry. Told apart from every other failure because it is proof of life: the
// exit heard the question and answered it, so a tunnel counting how its exit
// is doing must not count this against it. A dead website is not a dead exit.
var ErrTargetRefused = errors.New("CONNECT refused")

// windscribeSuffix is the domain every Windscribe exit is named under
// ("fr-030.totallyacdn.com"). It is what tells a Windscribe exit from a
// Surfshark one at dial time, from the name alone.
const windscribeSuffix = ".totallyacdn.com"

// sniFor is the name a handshake to this exit announces: the exit's own name
// for a Windscribe exit, nothing for anything else.
//
// The split is measured, not a matter of taste. On 2026-11-12, uploads from
// a server in Iran through seven Windscribe exits in Zurich, Frankfurt and
// Amsterdam (2 MB each to speed.cloudflare.com, three interleaved rounds)
// went like this:
//
//	no SNI                      0-17 KB/s on every exit, some refused outright
//	the exit's real name        1,237-1,915 KB/s on six of seven
//	an unrelated decoy name     4-17 KB/s - a decoy does not help
//
// and four uploads in parallel through one exit without SNI each crawled at
// 8-12 KB/s, so the limit is per connection, not per exit. The line throttles
// TLS that names nothing, and lets through TLS that names the certificate it
// gets back. Windscribe's names are not what the inspection kills, so they
// are sent.
//
// Surfshark stays without SNI. Its names were the reason for leaving SNI out
// in the first place (a handshake naming *.prod.surfshark.com was killed on
// the way), and the same day showed it no longer matters for speed from Iran:
// from that server the handshake failed on all 142 Surfshark clusters, with
// SNI, without it and with a decoy, while the same exits answered normally
// from a server outside Iran. That is the addresses being blocked, not the
// name, and nothing sent in the handshake changes it.
func sniFor(name string) string {
	if strings.HasSuffix(strings.ToLower(strings.TrimSuffix(name, ".")), windscribeSuffix) {
		return name
	}
	return ""
}

// Dial opens the TLS connection to the exit: to the pinned address, proving
// the name it was pinned from, and announcing that name only where it helps.
//
// This function is the reason the core is Go. Three settings have to hold at
// once and no two of them are usually allowed together:
//
//	ServerName: sniFor(Name)  the exit's real name for a Windscribe exit, which
//	                          is what keeps upload at full speed from Iran;
//	                          nothing for a Surfshark exit, where a handshake
//	                          that names *.prod.surfshark.com in the clear was
//	                          killed on the way out. See sniFor. A named
//	                          handshake the line cuts is retried once
//	                          without the name (sniWorked).
//
//	InsecureSkipVerify: true  not what it sounds like, and it is the only way
//	                          to reach the callback below. With an empty
//	                          ServerName, crypto/tls refuses to run at all
//	                          unless this is set - so it is set, and every
//	                          check it turns off is done by hand underneath.
//	                          Kept for Windscribe too, so both providers go
//	                          through the one check rather than two.
//
//	VerifyPeerCertificate     the chain still has to reach a system root, and
//	                          the leaf still has to serve the pinned name -
//	                          whether or not that name was sent.
//
// tls.Client rather than tls.Dial on purpose: tls.Dial fills ServerName in
// from the address it was given, which would put the exit's IP in the SNI
// extension and undo the first setting without saying so.
//
// A named handshake that is cut on the way - reset, closed, or never
// answered - is tried once more without the name (see sniWorked). One that
// fails the certificate check is not: that is an answer from the far end, and
// a nameless retry would only ask the same impostor again.
func (e *Exit) Dial(timeout time.Duration) (*tls.Conn, error) {
	sni := sniFor(e.Name)
	key := e.sniKey()
	worked, known := sniWorked.Load(key)
	if sni != "" && known && !worked.(bool) {
		// This exit, on this line, has already answered only without its
		// name. Asking with it again would cost every race a dead handshake.
		sni = ""
	}

	conn, shook, err := e.dialWith(sni, timeout)
	if sni == "" {
		return conn, err
	}
	if err == nil {
		sniWorked.Store(key, true)
		return conn, nil
	}
	// Retry only a handshake that was cut, and only for an exit whose named
	// handshake has not worked from here before - if it has, the name is not
	// the problem and the exit is simply down.
	if !shook || !droppedOnTheWay(err) || (known && worked.(bool)) {
		return nil, err
	}
	conn, _, err2 := e.dialWith("", timeout)
	if err2 != nil {
		return nil, err
	}
	sniWorked.Store(key, false)
	return conn, nil
}

// sniWorked, and why there is a retry at all. The real name as SNI was
// measured on 2026-11-12 from a datacentre server in Iran, where it is what
// keeps upload fast. A home or mobile line may filter differently, and a
// line that kills a handshake naming *.totallyacdn.com would otherwise lose
// Windscribe altogether. So a named handshake that is reset, closed or left
// unanswered is tried once more without the name, still verified against the
// same pinned name, and which of the two worked is remembered per exit for as
// long as the process runs - races and sweeps ask the same exits over and
// over, and should not pay for the dead handshake every time.
//
// Keyed by address, port and name: the same address under another name is
// another question.
var sniWorked sync.Map // sniKey -> bool (true: named worked; false: nameless only)

func (e *Exit) sniKey() string {
	return net.JoinHostPort(e.Addr, strconv.Itoa(e.Port)) + "|" + strings.ToLower(e.Name)
}

// droppedOnTheWay is whether a handshake failed the way a line cuts one -
// reset, closed, or not answered in time - rather than by an answer from the
// far end. A certificate that does not verify is an answer and is never in
// here: those errors come from verify, not from the socket. An alert from the
// server is an answer too.
func droppedOnTheWay(err error) bool {
	if errors.Is(err, ErrFiltered) || errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}
	// Something that is not TLS at all came back - a filter's block page
	// injected in place of the ServerHello. Measured on 2026-11-12 on a home
	// line in Iran: a hello naming *.totallyacdn.com got exactly this 0.3 s
	// in, while the same address without a name shook hands normally. Not
	// retrying here would lose Windscribe on that line altogether.
	var rh tls.RecordHeaderError
	if errors.As(err, &rh) {
		return true
	}
	// A reset arrives as the socket's own error, wrapped in *net.OpError -
	// ECONNRESET on Unix, WSAECONNRESET on Windows, and neither is spelled
	// the same way, so the wrapper is what is checked. Nothing verify returns
	// is one.
	var op *net.OpError
	return errors.As(err, &op)
}

// dialWith is one attempt: a fresh TCP connection and one handshake, sending
// sni (or nothing, when it is empty), verified by hand either way. shook says
// whether the handshake was reached at all: a connection that never opened
// sent no name, so leaving the name out cannot help it.
func (e *Exit) dialWith(sni string, timeout time.Duration) (_ *tls.Conn, shook bool, _ error) {
	deadline := time.Now().Add(timeout)

	// Control rather than a plain DialTimeout, because on Android this is
	// where the socket has to be marked as not belonging inside the tunnel.
	// See SetSocketProtector; on every other platform dialControl is nil and
	// this is an ordinary dial.
	dialer := net.Dialer{Timeout: timeout, Control: dialControl}
	raw, err := dialer.Dial("tcp", net.JoinHostPort(e.Addr, strconv.Itoa(e.Port)))
	if err != nil {
		return nil, false, err
	}
	if tcp, ok := raw.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}

	conn := tls.Client(raw, &tls.Config{
		ServerName:            sni,
		InsecureSkipVerify:    true,
		VerifyPeerCertificate: e.verify,
	})
	_ = conn.SetDeadline(deadline)
	if err := conn.Handshake(); err != nil {
		conn.Close()
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			return nil, true, ErrFiltered
		}
		return nil, true, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, true, nil
}

// exitRoots is the trust store verify checks exit chains against. Nil - the
// platform's own store - everywhere but in tests, which set it to a root of
// their own so a local stand-in exit can pass the real check rather than a
// weakened one.
var exitRoots *x509.CertPool

// verify is what InsecureSkipVerify turned off, done again by hand.
//
// Both halves matter and they fail differently, so they are kept apart: a
// chain that does not reach a root means the answer came from something that
// is not the exit, and a chain that verifies but serves the wrong name means
// this address is no longer the exit it was pinned as. Collapsing them into
// one error would make the second look like the first.
func (e *Exit) verify(rawCerts [][]byte, _ [][]*x509.Certificate) error {
	if len(rawCerts) == 0 {
		return errors.New("the exit offered no certificate")
	}
	certs := make([]*x509.Certificate, len(rawCerts))
	for i, der := range rawCerts {
		c, err := x509.ParseCertificate(der)
		if err != nil {
			return fmt.Errorf("unreadable certificate from %s: %w", e.Addr, err)
		}
		certs[i] = c
	}

	// Everything after the leaf is offered as a route to a root, not trusted
	// as one. Roots stay nil, which means the platform store - Android reads
	// /system/etc/security/cacerts, iOS goes through Security.framework, and
	// on a desktop it is whatever the machine already trusts.
	inter := x509.NewCertPool()
	for _, c := range certs[1:] {
		inter.AddCert(c)
	}
	if _, err := certs[0].Verify(x509.VerifyOptions{
		// nil outside tests: the platform store, as above.
		Roots:         exitRoots,
		Intermediates: inter,
		// Deliberately empty. The name is checked below instead, so that a
		// chain failure and a name failure arrive as different sentences.
		DNSName: "",
	}); err != nil {
		return fmt.Errorf("the certificate at %s does not verify: %w", e.Addr, err)
	}

	if e.Name == "" {
		return fmt.Errorf("%s was pinned without recording a name, so there is "+
			"nothing to check the certificate against", e.Addr)
	}
	// x509's own rule, which is the one OpenSSL would have applied: a
	// wildcard covers one label and no more.
	if err := certs[0].VerifyHostname(e.Name); err != nil {
		return fmt.Errorf("the certificate at %s does not serve %s", e.Addr, e.Name)
	}
	return nil
}

// Open asks the exit for a tunnel to target ("host:port") and hands back the
// connection with the CONNECT already answered, along with anything that
// arrived behind the answer.
//
// A CONNECT tunnel consumes its connection: once the exit answers 200 there
// is no framing to go back to, so this is never returned to a pool.
func (e *Exit) Open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	conn, err := e.Dial(timeout)
	if err != nil {
		return nil, nil, err
	}
	_ = conn.SetDeadline(time.Now().Add(timeout))
	spare, err := e.connect(conn, target)
	if err != nil {
		conn.Close()
		return nil, nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	return conn, spare, nil
}

// connect sends the CONNECT and reads exactly the head of the answer,
// returning anything that arrived behind it.
//
// Read by hand rather than with http.ReadResponse, and this was measured
// rather than preferred: a 200 to CONNECT carries no Content-Length and no
// chunked encoding, so net/http types the body as "read until the connection
// closes" - and Body.Close() then drains it. Against a live exit that meant
// waiting out the whole timeout: 8.34s, where the Python asking the same
// question answered in 0.53s. The slowness was the visible half. The other
// half is that the bytes it drained belong to the tunnel.
func (e *Exit) connect(conn *tls.Conn, target string) ([]byte, error) {
	if err := checkTarget(target); err != nil {
		return nil, err
	}
	req := "CONNECT " + target + " HTTP/1.1\r\n" +
		"Host: " + target + "\r\n" +
		"Proxy-Authorization: Basic " + e.auth + "\r\n\r\n"
	if _, err := conn.Write([]byte(req)); err != nil {
		return nil, err
	}

	var head []byte
	buf := make([]byte, 4096)
	for !bytes.Contains(head, headEnd) {
		n, err := conn.Read(buf)
		head = append(head, buf[:n]...)
		if err != nil {
			return nil, fmt.Errorf("the exit closed the connection on CONNECT: %w", err)
		}
		if len(head) > 32768 {
			return nil, errors.New("the exit answered CONNECT with nothing usable")
		}
	}

	end := bytes.Index(head, headEnd)
	spare := append([]byte(nil), head[end+len(headEnd):]...)
	status := string(bytes.SplitN(head[:end], []byte("\r\n"), 2)[0])

	code := ""
	if parts := strings.SplitN(status, " ", 3); len(parts) > 1 {
		code = parts[1]
	}
	switch {
	case code == "407":
		// Said plainly rather than as a status. On these exits it does not
		// mean the password is wrong - it means the account has no proxy on
		// it, which is a different thing to go and fix.
		return nil, errors.New(REFUSED)
	case !strings.HasPrefix(code, "2"):
		return nil, fmt.Errorf("%w: %s", ErrTargetRefused, strings.TrimSpace(status))
	}
	return spare, nil
}
