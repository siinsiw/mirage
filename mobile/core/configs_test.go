package core

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	sbdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	sbdirect "github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/vless"
	sjson "github.com/sagernet/sing/common/json"
)

const testUUID = "b831381d-6324-4d53-ad4f-8cda48b30811"

// Every fixture sing-box can carry becomes an outbound sing-box itself
// accepts: the field names and values are the ones it knows. Created, not
// started, so nothing is dialled.
func TestConfigsEveryFixtureIsAnOutboundSingboxTakes(t *testing.T) {
	for _, tc := range loadLinkFixtures(t).OK {
		c, err := ParseLink(tc.Link)
		if err != nil {
			t.Fatal(err)
		}
		for _, fragment := range []bool{false, true} {
			data, err := boxJSON([]*Config{c}, c.ID, false, fragment)
			if c.Engine() == "xray" {
				if err == nil {
					t.Errorf("%s: an Xray-only config was given to sing-box", tc.About)
				}
				continue
			}
			if err != nil {
				t.Fatalf("%s: %v", tc.About, err)
			}
			ctx := boxContext(context.Background())
			opts, err := sjson.UnmarshalExtendedContext[option.Options](ctx, data)
			if err != nil {
				t.Fatalf("%s: sing-box will not read it: %v\n%s", tc.About, err, data)
			}
			b, err := box.New(box.Options{Context: ctx, Options: opts})
			if err != nil {
				t.Fatalf("%s: sing-box will not make it: %v", tc.About, err)
			}
			b.Close()
		}
	}
}

func TestConfigsCertificateCheckStaysOn(t *testing.T) {
	c, err := ParseLink("trojan://pw@t.example.com:443?allowInsecure=1#t")
	if err != nil {
		t.Fatal(err)
	}
	ob, _ := Outbound(c, "t", false)
	if _, set := ob["tls"].(map[string]any)["insecure"]; set {
		t.Fatal("a link's ask turned the certificate check off")
	}
	c.AllowInsecure = true
	ob, _ = Outbound(c, "t", false)
	if ob["tls"].(map[string]any)["insecure"] != true {
		t.Fatal("the person's own switch did not")
	}
}

// vlessServer is a VLESS server on the loopback, made of sing-box too:
// enough to carry a real connection end to end without the internet.
func vlessServer(t *testing.T) int {
	t.Helper()
	l, _ := net.Listen("tcp", "127.0.0.1:0")
	port := l.Addr().(*net.TCPAddr).Port
	l.Close()
	ib := inbound.NewRegistry()
	vless.RegisterInbound(ib)
	ob := outbound.NewRegistry()
	sbdirect.RegisterOutbound(ob)
	ctx := box.Context(context.Background(), ib, ob, endpoint.NewRegistry(),
		testDNS(), service.NewRegistry(), certificate.NewRegistry())
	cfg := fmt.Sprintf(`{"log":{"disabled":true},"inbounds":[{"type":"vless","listen":"127.0.0.1",
	  "listen_port":%d,"users":[{"uuid":%q}]}],"outbounds":[{"type":"direct"}]}`, port, testUUID)
	opts, err := sjson.UnmarshalExtendedContext[option.Options](ctx, []byte(cfg))
	if err != nil {
		t.Fatal(err)
	}
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		t.Fatal(err)
	}
	if err := b.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	return port
}

func echoTCP(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { l.Close() })
	go func() {
		for {
			c, err := l.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	return l.Addr().String()
}

func echoUDP(t *testing.T) *net.UDPAddr {
	t.Helper()
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(buf[:n], from)
		}
	}()
	return pc.LocalAddr().(*net.UDPAddr)
}

func TestConfigsWayCarriesTCPAndUDPAndSwitches(t *testing.T) {
	port := vlessServer(t)
	text := fmt.Sprintf("vless://%s@127.0.0.1:%d?security=none#one\nvless://%s@127.0.0.1:9?security=none#dead",
		testUUID, port, testUUID)
	set, problems, err := ParseLinks(text, 10)
	if err != nil || len(set) != 2 || len(problems) != 0 {
		t.Fatalf("%v %v %v", set, problems, err)
	}
	w, err := NewConfigsWay(set, set[0].ID, false, false)
	if err != nil {
		t.Fatal(err)
	}
	defer w.Close()
	if w.Now() != set[0].ID {
		t.Fatalf("now %q, want %q", w.Now(), set[0].ID)
	}

	echo := echoTCP(t)
	conn, _, err := w.Open(echo, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write([]byte("hello")); err != nil {
		t.Fatal(err)
	}
	got := make([]byte, 5)
	if _, err := io.ReadFull(conn, got); err != nil || string(got) != "hello" {
		t.Fatalf("tcp echo: %q %v", got, err)
	}
	conn.Close()

	u := echoUDP(t)
	pc, err := w.ListenPacket(u.AddrPort())
	if err != nil {
		t.Fatal(err)
	}
	_ = pc.SetDeadline(time.Now().Add(5 * time.Second))
	if _, err := pc.WriteTo([]byte("ping"), u); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 64)
	n, _, err := pc.ReadFrom(buf)
	if err != nil || string(buf[:n]) != "ping" {
		t.Fatalf("udp echo: %q %v", buf[:n], err)
	}
	pc.Close()

	// Live switch to the dead one: new connections fail there, and back.
	if !w.Select(set[1].ID) || w.Now() != set[1].ID {
		t.Fatal("did not switch")
	}
	if _, _, err := w.Open(echo, 3*time.Second); err == nil {
		t.Fatal("a dead config carried a connection")
	}
	w.Select(set[0].ID)
	if c, _, err := w.Open(echo, 5*time.Second); err != nil {
		t.Fatal(err)
	} else {
		c.Close()
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := w.Open(echo, time.Second); err == nil {
		t.Fatal("a closed way still opened")
	}
}

func TestConfigsProbe(t *testing.T) {
	port := vlessServer(t)
	page := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/cdn-cgi/trace" {
			http.NotFound(w, r)
			return
		}
		io.WriteString(w, "fl=1\nip=203.0.113.99\nloc=DE\nwarp=off\n")
	}))
	defer page.Close()
	pool := x509.NewCertPool()
	pool.AddCert(page.Certificate())
	oldTarget, oldSNI, oldRoots := cfgProbeTarget, cfgProbeSNI, cfgProbeRoots
	cfgProbeTarget, cfgProbeSNI, cfgProbeRoots = page.Listener.Addr().String(), "example.com", pool
	defer func() { cfgProbeTarget, cfgProbeSNI, cfgProbeRoots = oldTarget, oldSNI, oldRoots }()

	text := fmt.Sprintf("vless://%s@127.0.0.1:%d?security=none#ok\nvless://%s@127.0.0.1:9?security=none#dead\n"+
		"vless://%s@x.example.com:443?security=tls&type=xhttp#xh", testUUID, port, testUUID, testUUID)
	set, _, _ := ParseLinks(text, 10)
	var seen []ProbeResult
	results, err := ProbeConfigs(set, false, 4, 5*time.Second, nil, func(d, total int, r ProbeResult) {
		seen = append(seen, r)
	})
	if err != nil || len(results) != 3 || len(seen) != 3 {
		t.Fatalf("%v %v", results, err)
	}
	by := map[string]ProbeResult{}
	for _, r := range results {
		by[r.ID] = r
	}
	if r := by[set[0].ID]; r.MS == 0 || r.Country != "de" || r.IP != "203.0.113.99" {
		t.Fatalf("the live one: %+v", r)
	}
	if r := by[set[1].ID]; r.MS != 0 || r.Error != "refused" {
		t.Fatalf("the dead one: %+v", r)
	}
	if r := by[set[2].ID]; r.Error != "xray-missing" {
		t.Fatalf("the xhttp one: %+v", r)
	}
}

func TestConfigsSubscription(t *testing.T) {
	f := loadLinkFixtures(t)
	var links []string
	for _, c := range f.OK[:4] {
		links = append(links, c.Link)
	}
	body := base64.StdEncoding.EncodeToString([]byte(strings.Join(links, "\n")))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/hop" {
			http.Redirect(w, r, "/sub", http.StatusFound)
			return
		}
		w.Header().Set("subscription-userinfo", "upload=5; download=6; total=100; expire=1767225600")
		w.Header().Set("profile-title", "base64:"+base64.StdEncoding.EncodeToString([]byte("فروشگاه‮")))
		io.WriteString(w, body)
	}))
	defer srv.Close()
	SetLive(nil)
	configs, problems, info, name, err := FetchSubscription(srv.URL+"/hop", 5*time.Second)
	if err != nil || len(configs) != 4 || len(problems) != 0 {
		t.Fatalf("%v %v %v", configs, problems, err)
	}
	if info == nil || info.Total != 100 || info.Expire != 1767225600 || name != "فروشگاه" {
		t.Fatalf("%+v %q", info, name)
	}
	for _, bad := range []string{"ftp://x.example.com/a", "https://u:p@x.example.com/a",
		"https://x.example.com/a b", "https://bad_host!/a", "javascript:1"} {
		if _, err := CheckSubURL(bad); err == nil {
			t.Errorf("%q was taken", bad)
		}
	}
}

func TestConfigsSubscriptionRefusesADowngrade(t *testing.T) {
	plain := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, "vless://"+testUUID+"@203.0.113.1:443?security=tls&sni=a.example.com#x")
	}))
	defer plain.Close()
	secure := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, plain.URL+"/sub", http.StatusFound)
	}))
	defer secure.Close()
	// The test server's certificate is its own; trusted for this request only.
	old := http.DefaultTransport.(*http.Transport).TLSClientConfig
	defer func() { http.DefaultTransport.(*http.Transport).TLSClientConfig = old }()
	SetLive(nil)
	_, _, _, _, err := fetchWith(secure.URL+"/", 5*time.Second, &tls.Config{RootCAs: certPool(secure)})
	if err == nil || !strings.Contains(err.Error(), "https to plain http") {
		t.Fatalf("followed a downgrade: %v", err)
	}
}

func certPool(s *httptest.Server) *x509.CertPool {
	p := x509.NewCertPool()
	p.AddCert(s.Certificate())
	return p
}

func testDNS() *sbdns.TransportRegistry {
	r := sbdns.NewTransportRegistry()
	local.RegisterTransport(r)
	return r
}
