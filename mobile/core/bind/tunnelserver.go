package mirage

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/siinsiw/ovpn-pin/mobile/core"
)

// The user's own server, as the phone sees it.
//
// The desktop reaches this by running gost as a child process and pointing
// its proxy at the local port that comes up. A phone does not spawn processes
// comfortably and does not have to: gost is Go, this core is Go, and the mwss
// path is a websocket, a smux session and a CONNECT.
//
// Everything here is flat for the same reason the rest of this package is:
// gomobile carries strings, ints and errors, so the edges arrive as one
// newline-separated string rather than as a slice.

// ServerConfig is what the phone needs to know to reach the server.
//
// A struct because gomobile does carry those, and because three loose string
// parameters in that order is the kind of signature that gets called with the
// passwords the wrong way round exactly once and then behaves strangely
// forever.
type ServerConfig struct {
	Domain string
	// Port is where the server takes TLS; 0 means 443. Only the installer's
	// --port makes it anything else, and the key then says so.
	Port     int
	Password string // the CONNECT credential
	Mode     string // "single", "multi" or "bulk"

	// Edges is the addresses to dial, newest measurement first, one per line.
	// Empty means dial the domain by name - which works on a line that is not
	// lying about this particular name, and is what the first run does before
	// anything has been measured.
	Edges string

	// Pin is the SHA-256 of the certificate a server reached by IP serves,
	// in hex: from the server's key, or what ServerFingerprint showed and the
	// person confirmed. A server showing anything else is refused - and a
	// server by address with no pin is not dialled at all (NoPinError).
	Pin string

	// Transport is how the server is reached: "" or "ws" for the WebSocket
	// (through Cloudflare, or straight to the address), "ss" for Shadowsocks
	// straight to the address. The SS fields come from the server's key.
	Transport  string
	SSMethod   string
	SSPassword string
	SSPort     int // exits at the server: single and bulk
	SSExitPort int // exits at the chosen provider location: multi
}

func NewServerConfig() *ServerConfig { return &ServerConfig{Mode: "single"} }

func (c *ServerConfig) edges() []string {
	var out []string
	for _, line := range strings.Split(c.Edges, "\n") {
		if s := strings.TrimSpace(line); s != "" {
			out = append(out, s)
		}
	}
	return out
}

// ScanEdges measures which Cloudflare addresses will carry the domain from
// this line, and returns them one per line, quickest first.
//
// The same question the desktop asks before it writes a gost config, and it
// has to be asked from the phone rather than copied from the desktop: which
// addresses are filtered is a fact about the line the phone is on, and the
// two are not always the same line.
//
// Blocking, and about five seconds - fifty-six addresses at twenty-eight at a
// time, and the blocked ones are what it waits for.
func ScanEdges(domain string, port, timeoutMs int) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(),
		time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	live, said, err := core.EdgesFor(ctx, domain, port, 2500*time.Millisecond)
	if err != nil {
		// The tally comes back with the error rather than instead of it:
		// "no edges" has four causes and only one of them is the server.
		return said, err
	}
	return strings.Join(live, "\n"), nil
}

// TestServer opens the tunnel, asks one question through it and reports where
// it came out. What a "test this server" button calls.
//
// Returns the same JSON shape as SeenAsJSON, so the window can show the
// answer with the code it already has.
func TestServer(cfg *ServerConfig, timeoutMs int) (string, error) {
	if cfg == nil || cfg.Domain == "" {
		return "", errors.New("no server to test")
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond

	srv := cfg.server()
	defer srv.Close()

	out, err := core.SeenAsThrough(srv, timeout)
	return withPin(out, srv.SeenPin()), err
}

// ServerFingerprint is the certificate a server by address shows right now,
// as the SHA-256 its pin is written in (hex, lower case) - the same number
// its installer prints beside "certificate sha256". What the settings screen
// shows the first time such a server is tested without a key, for the person
// to compare and confirm before it becomes cfg.Pin.
//
// Nothing but a TLS ClientHello is sent: no password, no request. Nothing is
// trusted or kept because of the answer. "" for a server by name (a CA
// vouches for it).
func ServerFingerprint(cfg *ServerConfig, timeoutMs int) (string, error) {
	if cfg == nil || cfg.Domain == "" {
		return "", errors.New("no server to ask")
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	srv := core.NewTunnelServer(cfg.Domain, "", cfg.edges(), core.ModeSingle)
	srv.Port = cfg.Port
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return srv.PeekPin(ctx, timeout)
}

// server is the client this config describes, pin and all.
//
// Over Shadowsocks the mode is a port rather than a path: multi dials the
// port that goes on through the server's exit-chain, the other two the one
// that exits at the server. Bulk has nothing to add there - Shadowsocks is
// one connection per connection already.
func (c *ServerConfig) server() core.ServerWay {
	if c.Transport == "ss" {
		port := c.SSPort
		if core.TunnelMode(c.Mode) == core.ModeMulti {
			port = c.SSExitPort
		}
		return core.NewShadowsocksServer(c.Domain, port, c.SSMethod, c.SSPassword)
	}
	srv := core.NewTunnelServer(c.Domain, c.Password, c.edges(), core.TunnelMode(c.Mode))
	srv.Pin = c.Pin
	srv.Port = c.Port
	return srv
}

// ParseKey reads the key the server's installer printed (mirage://..., or
// relay://... from one installed before the rename) and
// hands back what it says as JSON, checked: host, pin, password,
// apiPassword, port (the server's TLS port, 443 when the key has none) and,
// when the server offers it, ss {method, password, port, exitPort}. What the settings screen calls when a key is pasted.
func ParseKey(text string) (string, error) {
	k, err := core.ParseMirageKey(text)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(k)
	return string(out), err
}

// withPin adds the certificate the server showed to a seen-as answer. It is
// the pin the connection was already held to - a server by address is not
// dialled without one - so it tells the window nothing new to trust.
func withPin(seenAs, pin string) string {
	if pin == "" {
		return seenAs
	}
	var m map[string]any
	if json.Unmarshal([]byte(seenAs), &m) != nil || m == nil {
		m = map[string]any{}
	}
	m["pin"] = pin
	out, _ := json.Marshal(m)
	return string(out)
}

// SetServerExit points the server's multi-IP mode at one provider exit,
// with that exit's own provider credential - what server + exit does before
// it connects, so the location picked is the one traffic leaves by.
//
//	exitIP    the exit's pinned address (Result.Addr)
//	exitName  the name its certificate serves - the exit's pinned Name
//	          (Result.Name); required when verify is true
//	name      the node's label in the server's config (Result.File), or ""
//	verify    the server checks the exit's certificate against exitName.
//	          The per-server setting, on by default; off only when the person
//	          turned it off (see ServerInIran)
//
// A server by address whose pin is not known yet is refused with
// core.ErrNoPin's message before anything is sent: test the server first.
func SetServerExit(cfg *ServerConfig, apiPassword, exitIP, exitName, user, password, name string, verify bool, timeoutMs int) error {
	if cfg == nil || cfg.Domain == "" {
		return errors.New("no server configured")
	}
	return core.SetExit(cfg.Domain, cfg.Port, apiPassword, cfg.Pin, core.ExitHop{
		IP: exitIP, Hostname: exitName, User: user, Password: password, Node: name, Verify: verify,
	}, time.Duration(timeoutMs)*time.Millisecond)
}

// ServerInIran is whether the user's server is at an Iranian address, by the
// Iran split-tunnelling preset's ranges: host as an IPv4 address, or a name
// resolved over DoH (Cloudflare, pinned) - true when any of its addresses is
// in the list. The settings screen asks it for its Iran notices - keep the
// exit's certificate check (verify, above) on, because from Iran an exit
// reached without its name is held to about 10 KB/s of upload and with it
// runs at full speed (measured 2026-11-12); and Surfshark's exits cannot be
// reached from a server in Iran at all - and the service asks it when server
// + exit through Surfshark fails, to say why.
//
// An error is "could not tell" (a name that does not resolve, no network) -
// not a no.
func ServerInIran(host string, timeoutMs int) (bool, error) {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	return core.ServerInIran(ctx, host)
}

// NoPinError is core.ErrNoPin's message, so the window can tell that error
// from the others by comparing strings - the only way gomobile lets an error
// be told apart on the other side.
func NoPinError() string { return core.ErrNoPin.Error() }

// SeenAsLiveJSON is where the running tunnel comes out, asked through
// whatever is carrying it. What the window asks for the user's own server:
// SeenAsJSON rebuilds a provider exit from an address and a credential, and a
// server has neither of those to give it.
func SeenAsLiveJSON(timeoutMs int) string {
	w := core.LiveWay()
	if w == nil {
		return "{}"
	}
	out, _ := core.SeenAsThrough(w, time.Duration(timeoutMs)*time.Millisecond)
	return out
}

// LatestReleaseTag is the tag of the newest published Android release (the
// highest android-v* tag in this repository's releases), asked through the tunnel that is up.
// What a "check for a newer version" button calls while connected; the
// window compares it with its own version. An error when nothing is carrying
// - the window then decides whether to ask some other way - or when GitHub
// did not answer. Never called by the core on its own.
func LatestReleaseTag(timeoutMs int) (string, error) {
	w := core.LiveWay()
	if w == nil {
		return "", errors.New("not connected")
	}
	timeout := time.Duration(timeoutMs) * time.Millisecond
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	return core.LatestRelease(ctx, w, timeout)
}
