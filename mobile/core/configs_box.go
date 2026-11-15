package core

// Configs as sing-box: the phone's copy of app/configs/singbox.py's
// outbound(), and the Way that carries the tunnel through them.
//
// sing-box is a library here, not the whole program. Its outbounds - VLESS
// with Reality and Vision, VMess, Trojan, Shadowsocks, Hysteria2, TUIC - and
// its selector and urltest groups run inside a box with no inbounds at all.
// The tun, the DNS-over-HTTPS, split tunnelling, the host list, the health
// count and sharing on Wi-Fi stay this core's own (tunnel.go), and see a Way
// like any other: the same arrangement the provider exits and the user's own
// server already use. One VpnService, one stack, one way out at a time.
//
// Only the protocols the app reads are registered, so the binary carries
// those and not the rest of sing-box (measured 2026-11-14: about 13 MB more
// per architecture, uncompressed, and 1.4 MB more heap with fifty configs
// loaded).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/certificate"
	"github.com/sagernet/sing-box/adapter/endpoint"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/adapter/service"
	sbdns "github.com/sagernet/sing-box/dns"
	"github.com/sagernet/sing-box/dns/transport"
	"github.com/sagernet/sing-box/dns/transport/local"
	"github.com/sagernet/sing-box/option"
	sbdirect "github.com/sagernet/sing-box/protocol/direct"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/protocol/hysteria2"
	"github.com/sagernet/sing-box/protocol/shadowsocks"
	"github.com/sagernet/sing-box/protocol/trojan"
	"github.com/sagernet/sing-box/protocol/tuic"
	"github.com/sagernet/sing-box/protocol/vless"
	"github.com/sagernet/sing-box/protocol/vmess"
	sjson "github.com/sagernet/sing/common/json"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	sservice "github.com/sagernet/sing/service"
)

// ConfigsTestURL is what auto (urltest) and the latency test ask for: the
// trace page this core already asks for its "seen as" (seenas.go).
const ConfigsTestURL = "https://1.1.1.1/cdn-cgi/trace"

const (
	configsProxy    = "proxy"
	configsAuto     = "auto"
	configsDirect   = "direct"
	configsAutoMost = 50
)

// ErrNeedsXray is a config only Xray carries (xhttp, and a few older
// shapes). The Windows app runs Xray beside sing-box for those; the phone
// does not carry Xray yet.
var ErrNeedsXray = errors.New("xray-missing: this config needs Xray (xhttp), which the phone app does not carry yet")

func configTag(c *Config) string { return "c-" + c.ID }

func serverName(c *Config) string {
	switch {
	case c.SNI != "":
		return c.SNI
	case c.HostHeader != "":
		return c.HostHeader
	case isAddress(c.Host):
		return ""
	}
	return c.Host
}

func tlsBlock(c *Config, fragment bool) map[string]any {
	if c.Security != "tls" && c.Security != "reality" {
		return nil
	}
	t := map[string]any{"enabled": true}
	if name := serverName(c); name != "" {
		t["server_name"] = name
	}
	alpn := append([]string{}, c.ALPN...)
	if c.Net == "ws" || c.Net == "httpupgrade" {
		// A WebSocket upgrade is HTTP/1.1; offering h2 lets a CDN pick it
		// and the upgrade then never happens.
		var keep []string
		for _, a := range alpn {
			if a != "h2" {
				keep = append(keep, a)
			}
		}
		alpn = keep
	}
	if c.Net == "grpc" {
		alpn = nil // sing-box sets h2 itself for gRPC
	}
	if len(alpn) > 0 {
		t["alpn"] = alpn
	}
	if c.FP != "" {
		t["utls"] = map[string]any{"enabled": true, "fingerprint": c.FP}
	}
	if c.Security == "reality" {
		t["reality"] = map[string]any{"enabled": true, "public_key": c.PBK, "short_id": c.SID}
	} else if c.AllowInsecure {
		// Only from the person's own switch for this one server - never
		// from the link asking for it (links.go keeps the two apart).
		t["insecure"] = true
	}
	if fragment {
		t["fragment"] = true
		t["record_fragment"] = true
	}
	return t
}

func quicTLS(c *Config) map[string]any {
	t := map[string]any{"enabled": true}
	if name := serverName(c); name != "" {
		t["server_name"] = name
	}
	if len(c.ALPN) > 0 {
		t["alpn"] = c.ALPN
	}
	if c.AllowInsecure {
		t["insecure"] = true
	}
	return t
}

func transportBlock(c *Config) map[string]any {
	switch c.Net {
	case "ws":
		t := map[string]any{"type": "ws", "path": defaultTo(c.Path, "/")}
		if c.HostHeader != "" {
			t["headers"] = map[string]any{"Host": c.HostHeader}
		}
		if c.ED > 0 {
			t["max_early_data"] = c.ED
			t["early_data_header_name"] = "Sec-WebSocket-Protocol"
		}
		return t
	case "grpc":
		return map[string]any{"type": "grpc", "service_name": c.Service}
	case "httpupgrade":
		t := map[string]any{"type": "httpupgrade", "path": defaultTo(c.Path, "/")}
		if c.HostHeader != "" {
			t["host"] = c.HostHeader
		}
		return t
	case "http":
		t := map[string]any{"type": "http", "path": defaultTo(c.Path, "/")}
		if c.HostHeader != "" {
			t["host"] = []string{c.HostHeader}
		}
		return t
	}
	return nil
}

// Outbound is one config as a sing-box outbound, the way app/configs/
// singbox.py makes it.
func Outbound(c *Config, tag string, fragment bool) (map[string]any, error) {
	if c.Engine() == "xray" {
		return nil, ErrNeedsXray
	}
	base := map[string]any{"tag": tag, "server": c.Host, "server_port": c.Port}
	with := func(kv ...any) map[string]any {
		m := map[string]any{}
		for k, v := range base {
			m[k] = v
		}
		for i := 0; i+1 < len(kv); i += 2 {
			m[kv[i].(string)] = kv[i+1]
		}
		return m
	}
	var ob map[string]any
	switch c.Proto {
	case "ss":
		return with("type", "shadowsocks", "method", c.Method, "password", c.Password), nil
	case "hysteria2":
		ob = with("type", "hysteria2", "password", c.Password, "tls", quicTLS(c))
		if c.Obfs != "" {
			ob["obfs"] = map[string]any{"type": "salamander", "password": c.ObfsPassword}
		}
		return ob, nil
	case "tuic":
		return with("type", "tuic", "uuid", c.UUID, "password", c.Password,
			"congestion_control", defaultTo(c.Congestion, "bbr"),
			"udp_relay_mode", defaultTo(c.UDPRelay, "native"), "tls", quicTLS(c)), nil
	case "vless":
		ob = with("type", "vless", "uuid", c.UUID, "packet_encoding", "xudp")
		if c.Flow != "" {
			ob["flow"] = c.Flow
		}
	case "vmess":
		ob = with("type", "vmess", "uuid", c.UUID, "security", defaultTo(c.Cipher, "auto"),
			"alter_id", c.AlterID, "packet_encoding", "xudp")
	case "trojan":
		ob = with("type", "trojan", "password", c.Password)
	default:
		return nil, fmt.Errorf("unknown protocol %q", c.Proto)
	}
	if t := tlsBlock(c, fragment); t != nil {
		ob["tls"] = t
	}
	if t := transportBlock(c); t != nil {
		ob["transport"] = t
	}
	return ob, nil
}

// boxJSON is the whole box for one group: every config of it an outbound,
// a selector "proxy" choosing among them, and with auto a urltest that picks
// the fastest of the first fifty and moves on when one stops answering.
// Configs only Xray carries are left out; picking one is an error.
func boxJSON(set []*Config, selected string, auto, fragment bool) ([]byte, error) {
	var obs []any
	var tags []string
	chosen := ""
	for _, c := range set {
		ob, err := Outbound(c, configTag(c), fragment)
		if errors.Is(err, ErrNeedsXray) {
			if c.ID == selected && !auto {
				return nil, err
			}
			continue
		}
		if err != nil {
			return nil, err
		}
		obs = append(obs, ob)
		tags = append(tags, configTag(c))
		if c.ID == selected {
			chosen = configTag(c)
		}
	}
	if len(tags) == 0 {
		return nil, errors.New("no config in this group can be carried on the phone")
	}
	members := append([]string{}, tags...)
	def := chosen
	if def == "" {
		def = tags[0]
	}
	if auto {
		first := tags
		if len(first) > configsAutoMost {
			first = first[:configsAutoMost]
		}
		members = append([]string{configsAuto}, members...)
		obs = append(obs, map[string]any{"type": "urltest", "tag": configsAuto,
			"outbounds": first, "url": ConfigsTestURL, "interval": "10m",
			"tolerance": 50, "idle_timeout": "30m",
			"interrupt_exist_connections": true})
		def = configsAuto
	}
	obs = append(obs,
		map[string]any{"type": "selector", "tag": configsProxy, "outbounds": members,
			"default": def, "interrupt_exist_connections": true},
		map[string]any{"type": "direct", "tag": configsDirect})
	return json.Marshal(map[string]any{
		"log": map[string]any{"disabled": true},
		// The servers' own names are asked over HTTPS straight to 1.1.1.1:
		// the line's resolver is the one that forges answers.
		"dns": map[string]any{
			"servers":  []any{map[string]any{"type": "https", "tag": "doh", "server": "1.1.1.1"}},
			"strategy": "ipv4_only"},
		"outbounds": obs,
		"route":     map[string]any{"final": configsProxy, "default_domain_resolver": "doh"},
	})
}

// boxContext has only what the app reads registered, and the platform stub
// (configs_platform.go) in place of Android's own.
func boxContext(ctx context.Context) context.Context {
	ob := outbound.NewRegistry()
	sbdirect.RegisterOutbound(ob)
	group.RegisterSelector(ob)
	group.RegisterURLTest(ob)
	vless.RegisterOutbound(ob)
	vmess.RegisterOutbound(ob)
	trojan.RegisterOutbound(ob)
	shadowsocks.RegisterOutbound(ob)
	hysteria2.RegisterOutbound(ob)
	tuic.RegisterOutbound(ob)
	dr := sbdns.NewTransportRegistry()
	transport.RegisterHTTPS(dr)
	transport.RegisterUDP(dr)
	local.RegisterTransport(dr)
	ctx = box.Context(ctx, inbound.NewRegistry(), ob, endpoint.NewRegistry(), dr,
		service.NewRegistry(), certificate.NewRegistry())
	return sservice.ContextWith[adapter.PlatformInterface](ctx, platformStub{})
}

// startBox builds and starts a box from its JSON.
func startBox(data []byte) (*box.Box, error) {
	ctx := boxContext(context.Background())
	opts, err := sjson.UnmarshalExtendedContext[option.Options](ctx, data)
	if err != nil {
		return nil, err
	}
	b, err := box.New(box.Options{Context: ctx, Options: opts})
	if err != nil {
		return nil, err
	}
	if err := b.Start(); err != nil {
		b.Close()
		return nil, err
	}
	return b, nil
}

// ConfigsWay is the tunnel's way out through a group of configs.
type ConfigsWay struct {
	box    *box.Box
	proxy  *group.Selector
	auto   bool
	ids    map[string]string // tag -> id
	closed atomic.Bool
	mu     sync.Mutex
}

var (
	_ ServerWay = (*ConfigsWay)(nil)
	_ PacketWay = (*ConfigsWay)(nil)
)

// NewConfigsWay starts sing-box for one group. selected is the id of the
// config to start on; with auto the fastest is picked instead.
func NewConfigsWay(set []*Config, selected string, auto, fragment bool) (*ConfigsWay, error) {
	data, err := boxJSON(set, selected, auto, fragment)
	if err != nil {
		return nil, err
	}
	b, err := startBox(data)
	if err != nil {
		return nil, err
	}
	ob, ok := b.Outbound().Outbound(configsProxy)
	sel, isSel := ob.(*group.Selector)
	if !ok || !isSel {
		b.Close()
		return nil, errors.New("sing-box did not make the selector")
	}
	ids := map[string]string{}
	for _, c := range set {
		ids[configTag(c)] = c.ID
	}
	return &ConfigsWay{box: b, proxy: sel, auto: auto, ids: ids}, nil
}

// dialed is a dial that may outlive its wait: the context sing-box is given
// is never cancelled (some transports tie the connection's life to it), and
// one that answers after the caller gave up is closed instead of leaked.
func dialed(timeout time.Duration, dial func() (net.Conn, error)) (net.Conn, error) {
	type result struct {
		c   net.Conn
		err error
	}
	done := make(chan result, 1)
	go func() {
		c, err := dial()
		done <- result{c, err}
	}()
	t := time.NewTimer(timeout)
	defer t.Stop()
	select {
	case r := <-done:
		return r.c, r.err
	case <-t.C:
		go func() {
			if r := <-done; r.c != nil {
				r.c.Close()
			}
		}()
		return nil, fmt.Errorf("i/o timeout after %s", timeout)
	}
}

// Open asks for a tunnel to target ("host:port") through the chosen config.
func (w *ConfigsWay) Open(target string, timeout time.Duration) (net.Conn, []byte, error) {
	if w.closed.Load() {
		return nil, nil, errors.New("this connection has been closed")
	}
	dest := M.ParseSocksaddr(target)
	if !dest.IsValid() {
		return nil, nil, fmt.Errorf("not a host and port: %q", target)
	}
	conn, err := dialed(timeout, func() (net.Conn, error) {
		return w.proxy.DialContext(context.Background(), N.NetworkTCP, dest)
	})
	return conn, nil, err
}

// ListenPacket is UDP through the chosen config, to one destination - what
// lets calls, games and QUIC work, which the provider exits cannot carry.
func (w *ConfigsWay) ListenPacket(destination netip.AddrPort) (net.PacketConn, error) {
	if w.closed.Load() {
		return nil, errors.New("this connection has been closed")
	}
	return w.proxy.ListenPacket(context.Background(), M.SocksaddrFromNetIP(destination))
}

// Select moves new connections to another config of the group, live.
func (w *ConfigsWay) Select(id string) bool {
	if w.auto {
		return false
	}
	return w.proxy.SelectOutbound("c-" + id)
}

// Now is the id of the config carrying, with auto the one it picked.
func (w *ConfigsWay) Now() string {
	ob := w.proxy.Selected(N.NetworkTCP)
	if ut, ok := ob.(*group.URLTest); ok {
		ob = ut.Selected(N.NetworkTCP)
	}
	if ob == nil {
		return ""
	}
	return w.ids[ob.Tag()]
}

// Close stops sing-box for good.
func (w *ConfigsWay) Close() error {
	if w.closed.Swap(true) {
		return nil
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.box.Close()
}

// Reset has nothing held between connections to drop.
func (w *ConfigsWay) Reset() error { return nil }

// SeenPin is empty: the certificates are sing-box's to check.
func (w *ConfigsWay) SeenPin() string { return "" }

// PacketWay is a Way that can carry UDP as well. tunnel.go asks for it; the
// provider exits and the user's own server are not one.
type PacketWay interface {
	Way
	ListenPacket(destination netip.AddrPort) (net.PacketConn, error)
}
