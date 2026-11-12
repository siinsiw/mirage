package core

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// Which name an exit's handshake announces decides, from a server in Iran,
// whether an upload runs at megabytes or at 10 KB/s (measured 2026-11-12, see
// sniFor). It is invisible in every other test: a dial that sends the wrong
// name, or none, still connects. So this stands up a TLS server, lets
// Exit.Dial talk to it, and reads the server_name out of the ClientHello it
// actually received.
//
// The handshake itself fails - the test certificate does not reach a system
// root, and the hand verification refuses it, as it should. The name is
// already on the wire by then, which is all this asks about.
func helloNameFrom(t *testing.T, name string) string {
	t.Helper()

	seen := make(chan string, 1)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
	srv.TLS = &tls.Config{GetConfigForClient: func(h *tls.ClientHelloInfo) (*tls.Config, error) {
		select {
		case seen <- h.ServerName:
		default:
		}
		return nil, nil
	}}
	// The refused handshake is the expected outcome; it is not worth a line
	// in the test output every run.
	srv.Config.ErrorLog = log.New(io.Discard, "", 0)
	srv.StartTLS()
	defer srv.Close()

	host, port, err := net.SplitHostPort(srv.Listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	e := NewExit(host, name, "u", "p")
	e.Port, _ = strconv.Atoi(port)

	conn, err := e.Dial(3 * time.Second)
	if err == nil {
		conn.Close()
		t.Fatalf("a self-signed test certificate was accepted for %s - the "+
			"hand verification is not running", name)
	}

	select {
	case got := <-seen:
		return got
	case <-time.After(3 * time.Second):
		t.Fatal("the test server never saw a ClientHello")
		return ""
	}
}

func TestWindscribeExitIsDialledWithItsName(t *testing.T) {
	const name = "ch-019.totallyacdn.com"
	if got := helloNameFrom(t, name); got != name {
		t.Fatalf("a Windscribe exit announced %q, wanted its own name %q - "+
			"without it, uploads from Iran crawl at about 10 KB/s", got, name)
	}
}

func TestSurfsharkExitIsDialledWithoutAName(t *testing.T) {
	if got := helloNameFrom(t, "de-fra.prod.surfshark.com"); got != "" {
		t.Fatalf("a Surfshark exit announced %q, wanted no name at all", got)
	}
}

func TestSNIIsChosenByTheExitsDomain(t *testing.T) {
	for name, want := range map[string]string{
		"fr-030.totallyacdn.com":       "fr-030.totallyacdn.com",
		"FR-030.TotallyACDN.com":       "FR-030.TotallyACDN.com",
		"de-fra.prod.surfshark.com":    "",
		"totallyacdn.com.evil.example": "",
		"nottotallyacdn.com":           "",
		"":                             "",
	} {
		if got := sniFor(name); got != want {
			t.Errorf("sniFor(%q) = %q, wanted %q", name, got, want)
		}
	}
}

// -- the nameless retry ---------------------------------------------------------
//
// The real name was measured from one datacentre line; a home or mobile line
// may cut a handshake that carries it. Dial then tries once without the name
// - still verified - and remembers which worked. These stand a local exit up
// behind a certificate the test trusts (exitRoots), so the check that runs is
// the real one and a pass means it passed.

// testCert is a self-signed certificate for names and a pool that trusts it.
func testCert(t *testing.T, names ...string) (tls.Certificate, *x509.CertPool) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: names[0]},
		DNSNames:              names,
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	leaf, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(leaf)
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key, Leaf: leaf}, pool
}

// trustOnly points verify at pool until the returned func puts it back.
func trustOnly(pool *x509.CertPool) func() {
	was := exitRoots
	exitRoots = pool
	return func() { exitRoots = was }
}

// standIn is a local exit. It reads each ClientHello first, counts whether
// it named anything, and - when cutNamed is set - resets every connection
// whose hello carries a name, the way a filtering line does. Everything else
// gets a real TLS handshake with cert.
type standIn struct {
	ln              net.Listener
	named, nameless atomic.Int32
}

type replayConn struct {
	net.Conn
	r io.Reader
}

func (c *replayConn) Read(b []byte) (int, error) { return c.r.Read(b) }

func startStandIn(t *testing.T, cert tls.Certificate, cutNamed bool) *standIn {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &standIn{ln: ln}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go s.serve(c, cert, cutNamed)
		}
	}()
	return s
}

func (s *standIn) serve(c net.Conn, cert tls.Certificate, cutNamed bool) {
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(5 * time.Second))
	head := make([]byte, 5)
	if _, err := io.ReadFull(c, head); err != nil {
		return
	}
	body := make([]byte, int(head[3])<<8|int(head[4]))
	if _, err := io.ReadFull(c, body); err != nil {
		return
	}
	hello := append(head, body...)
	if sniFrom(hello) != "" {
		s.named.Add(1)
		if cutNamed {
			if tcp, ok := c.(*net.TCPConn); ok {
				_ = tcp.SetLinger(0) // close with a reset, not a FIN
			}
			return
		}
	} else {
		s.nameless.Add(1)
	}
	srv := tls.Server(&replayConn{c, io.MultiReader(bytes.NewReader(hello), c)},
		&tls.Config{Certificates: []tls.Certificate{cert}})
	if err := srv.Handshake(); err != nil {
		return
	}
	_, _ = io.Copy(io.Discard, srv)
}

func (s *standIn) exit(name string) *Exit {
	host, port, _ := net.SplitHostPort(s.ln.Addr().String())
	e := NewExit(host, name, "u", "p")
	e.Port, _ = strconv.Atoi(port)
	return e
}

func TestCutNamedHandshakeFallsBackToNameless(t *testing.T) {
	const name = "ch-019.totallyacdn.com"
	cert, pool := testCert(t, name)
	defer trustOnly(pool)()
	s := startStandIn(t, cert, true)
	e := s.exit(name)

	conn, err := e.Dial(3 * time.Second)
	if err != nil {
		t.Fatalf("a line that cuts the named handshake left no way in: %v", err)
	}
	conn.Close()
	if n, m := s.named.Load(), s.nameless.Load(); n != 1 || m != 1 {
		t.Fatalf("wanted one named hello then one nameless, got %d named and %d nameless", n, m)
	}

	// Remembered: the next dial to the same exit does not pay for the dead
	// named handshake again.
	conn, err = e.Dial(3 * time.Second)
	if err != nil {
		t.Fatalf("the second dial failed: %v", err)
	}
	conn.Close()
	if n, m := s.named.Load(), s.nameless.Load(); n != 1 || m != 2 {
		t.Fatalf("the second dial asked with the name again: %d named, %d nameless", n, m)
	}
}

func TestNamedHandshakeThatWorksIsKept(t *testing.T) {
	const name = "nl-005.totallyacdn.com"
	cert, pool := testCert(t, name)
	defer trustOnly(pool)()
	s := startStandIn(t, cert, false)

	conn, err := s.exit(name).Dial(3 * time.Second)
	if err != nil {
		t.Fatalf("a stand-in exit with a trusted certificate was refused: %v", err)
	}
	conn.Close()
	if n, m := s.named.Load(), s.nameless.Load(); n != 1 || m != 0 {
		t.Fatalf("wanted exactly one named hello, got %d named and %d nameless", n, m)
	}
}

// A certificate that fails the check is an answer from the far end, not a
// cut line: no nameless retry, and the check is not softened by trying one.
func TestCertificateFailureIsNotRetriedWithoutTheName(t *testing.T) {
	for _, c := range []struct {
		why, served, dialled string
		trusted              bool
		says                 string
	}{
		{"wrong name", "ch-019.totallyacdn.com", "ch-020.totallyacdn.com", true, "does not serve"},
		{"untrusted chain", "de-001.totallyacdn.com", "de-001.totallyacdn.com", false, "does not verify"},
	} {
		cert, pool := testCert(t, c.served)
		restore := trustOnly(pool)
		if !c.trusted {
			restore()
			restore = trustOnly(x509.NewCertPool())
		}
		s := startStandIn(t, cert, false)
		conn, err := s.exit(c.dialled).Dial(3 * time.Second)
		restore()
		if err == nil {
			conn.Close()
			t.Fatalf("%s: the connection was accepted", c.why)
		}
		if !strings.Contains(err.Error(), c.says) {
			t.Fatalf("%s: refused for the wrong reason: %v", c.why, err)
		}
		if n, m := s.named.Load(), s.nameless.Load(); n != 1 || m != 0 {
			t.Fatalf("%s: wanted one named hello and no retry, got %d named and %d nameless", c.why, n, m)
		}
	}
}
