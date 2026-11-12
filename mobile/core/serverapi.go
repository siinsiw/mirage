package core

// The user's own server's API: where server + exit is told which provider
// exit to leave by. The desktop's tunnel.set_exit, for the phone.
//
// One PUT, and the change takes effect on the next connection through /ex,
// with nothing restarted at either end. The account goes with it - the
// chosen exit's own provider's - so the server never needs one of its own
// and the install line never carries one.

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

// ExitHop is the provider exit a chain on the server leaves by, as the
// server is to dial it.
type ExitHop struct {
	IP       string // the exit's pinned address
	Hostname string // the name its certificate serves - the pinned Name
	User     string // the exit's own provider's credential
	Password string
	// Node is what the node is called in the server's config ("current"
	// when empty) - a label, nothing is checked against it.
	Node string
	// Verify has the server check the exit's certificate against Hostname,
	// as the phone does in exit.go. The default for every server; turned off
	// only by the person, for a server whose own line kills a handshake that
	// names the provider (one inside Iran - see ServerInIran), where checking
	// would mean no exit works at all.
	Verify bool
}

// SetExit points the server's multi-IP mode at one provider exit, with that
// exit's provider credential. host is the server's IP or domain and port its
// TLS port (0 for 443); pin is its certificate's SHA-256 when it is reached
// by IP.
func SetExit(host string, port int, apiPassword, pin string, hop ExitHop, timeout time.Duration) error {
	return setChain(host, port, apiPassword, pin, "exit-chain", hop, timeout)
}

// SetSlot points one exported location - loc-<k> on the server, which the
// links handed to other devices are routed to - at one exit. The same node as
// SetExit writes, under another chain's name.
func SetSlot(host string, port int, apiPassword, pin string, k int, hop ExitHop, timeout time.Duration) error {
	if k < 1 {
		return fmt.Errorf("slot %d is not one the server has: they start at 1", k)
	}
	return setChain(host, port, apiPassword, pin, fmt.Sprintf("loc-%d", k), hop, timeout)
}

// chainBody is the gost chain that leaves by hop, as the API takes it.
//
// The dialer's tls block is where verification is decided. "secure": true
// with the exit's hostname as serverName is gost checking the chain and the
// name the way exit.go does; "secure": false is the old form, which takes
// whatever the address shows - kept for the person who turned checking off
// on purpose, and only for them.
func chainBody(chain string, hop ExitHop) ([]byte, error) {
	node := hop.Node
	if node == "" {
		node = "current"
	}
	tlsOpts := map[string]any{"secure": false}
	if hop.Verify {
		if hop.Hostname == "" || !validHost(hop.Hostname) || net.ParseIP(hop.Hostname) != nil {
			return nil, fmt.Errorf("%s was pinned without the name its certificate serves, so the server has nothing to check it against", hop.IP)
		}
		tlsOpts = map[string]any{"secure": true, "serverName": hop.Hostname}
	}
	return json.Marshal(map[string]any{
		"name": chain,
		"hops": []any{map[string]any{"name": "exit", "nodes": []any{map[string]any{
			"name":      node,
			"addr":      net.JoinHostPort(hop.IP, "443"),
			"connector": map[string]any{"type": "http", "auth": map[string]any{"username": hop.User, "password": hop.Password}},
			"dialer":    map[string]any{"type": "tls", "tls": tlsOpts},
		}}}},
	})
}

func setChain(host string, port int, apiPassword, pin, chain string, hop ExitHop, timeout time.Duration) error {
	body, err := chainBody(chain, hop)
	if err != nil {
		return err
	}
	_, err = ServerAPI(host, port, apiPassword, pin, http.MethodPut, "config/chains/"+chain, body, timeout)
	return err
}

// ServerInIran is whether the user's server sits at an Iranian address -
// by the Iran preset's own ranges, for an address as it is and for a name
// as DoH answers it (any one of its addresses is enough). What the settings
// screen asks before offering to turn ExitHop.Verify off: from inside Iran a
// handshake that names the provider is the kind the line kills, and checking
// the exit's certificate needs the name in it.
//
// An error is "could not tell" - a name that would not resolve - and is not
// a no.
func ServerInIran(ctx context.Context, host string) (bool, error) {
	host = strings.TrimSpace(host)
	if a, err := netip.ParseAddr(host); err == nil {
		return InIranIPv4(a), nil
	}
	if !validHost(host) {
		return false, fmt.Errorf("%q is not an address or a name", host)
	}
	addrs, err := Resolve(ctx, nil, "cloudflare", host)
	if err != nil {
		return false, err
	}
	for _, a := range addrs {
		if InIranIPv4(a) {
			return true, nil
		}
	}
	return false, nil
}

// ChainJSON is one chain as the server has it now, for putting back later -
// what an export does with the server's own exit-chain, which finding each
// location's best exit moves about.
func ChainJSON(host string, port int, apiPassword, pin, chain string, timeout time.Duration) (string, error) {
	out, err := ServerAPI(host, port, apiPassword, pin, http.MethodGet, "config/chains/"+chain, nil, timeout)
	if err != nil {
		return "", err
	}
	// gost wraps the object: {"data": {...}}.
	var wrapped struct {
		Data json.RawMessage `json:"data"`
	}
	if json.Unmarshal(out, &wrapped) != nil || len(wrapped.Data) == 0 || string(wrapped.Data) == "null" {
		return "", fmt.Errorf("the server has no chain called %s", chain)
	}
	return string(wrapped.Data), nil
}

// PutChainJSON puts back a chain ChainJSON read.
func PutChainJSON(host string, port int, apiPassword, pin, chain, body string, timeout time.Duration) error {
	_, err := ServerAPI(host, port, apiPassword, pin, http.MethodPut, "config/chains/"+chain, []byte(body), timeout)
	return err
}

// PersistServer writes the server's running configuration to the file it
// starts from, so what was set through the API outlives a restart. Without
// it, exported locations would all go back to the installer's placeholder -
// one that refuses - the next time the server rebooted.
func PersistServer(host string, port int, apiPassword, pin string, timeout time.Duration) error {
	_, err := ServerAPI(host, port, apiPassword, pin, http.MethodPost,
		"config?format=yaml&path=/etc/gost/config.yaml", nil, timeout)
	return err
}

// apiHost is the host part of the API's URL: the server's own TLS port when
// the installer was given one other than 443, nothing extra otherwise.
func apiHost(host string, port int) string {
	if tlsPort(port) == 443 {
		return host
	}
	return net.JoinHostPort(host, strconv.Itoa(tlsPort(port)))
}

// ErrNoPin is the API asked of a server by address before its certificate
// is known. A pin left empty there would trust whatever answered first and
// hand it the API password - so the request is not made at all, and the
// caller tests the server (which learns the pin; see TunnelServer.learnPin)
// before asking again.
var ErrNoPin = errors.New("the server's certificate is not pinned yet - test the server first")

// apiTransports holds one transport per server and pin, so the requests an
// export makes one after another share a connection rather than paying a
// TLS handshake each - and nothing is left holding sockets: idle ones are
// closed after apiIdle.
var (
	apiMu         sync.Mutex
	apiTransports = map[string]*http.Transport{}
)

const (
	apiIdle      = 30 * time.Second
	apiMaxCached = 8
)

// apiTransport is the transport for one server, made the first time it is
// asked for. Keyed by the pin as well as the host: a pin cleared or changed
// by hand must not ride a connection made under the old one.
func apiTransport(host string, port int, pin string) *http.Transport {
	key := apiHost(host, port) + "|" + strings.ToLower(pin)
	apiMu.Lock()
	defer apiMu.Unlock()
	if tr, ok := apiTransports[key]; ok {
		return tr
	}
	if len(apiTransports) >= apiMaxCached {
		// A phone talks to one server, two when one is being replaced; more
		// than this is old pins. Start again rather than keep them.
		for k, tr := range apiTransports {
			tr.CloseIdleConnections()
			delete(apiTransports, k)
		}
	}
	tr := &http.Transport{
		// No proxy: the API is asked straight, never through whatever the
		// environment names.
		Proxy: nil,
		// The per-request timeout is the context's; this only stops a dial
		// from outliving it on a line that swallows SYNs.
		DialContext:         (&net.Dialer{Timeout: 30 * time.Second, Control: dialControl}).DialContext,
		TLSHandshakeTimeout: 30 * time.Second,
		IdleConnTimeout:     apiIdle,
		MaxIdleConnsPerHost: 2,
		ForceAttemptHTTP2:   false,
	}
	if net.ParseIP(host) != nil {
		tr.TLSClientConfig = PinnedTLS(pin, nil)
	}
	apiTransports[key] = tr
	return tr
}

// ServerAPI is one request to the server's API, straight to it - protected
// out of the phone's own tunnel, and pinned when the server is an address.
// An address with no pin is ErrNoPin, never a request.
func ServerAPI(host string, port int, apiPassword, pin, method, path string, body []byte, timeout time.Duration) ([]byte, error) {
	if net.ParseIP(host) != nil && strings.TrimSpace(pin) == "" {
		return nil, ErrNoPin
	}
	client := &http.Client{Timeout: timeout, Transport: apiTransport(host, port, pin)}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, "https://"+apiHost(host, port)+"/api/"+path, reader)
	if err != nil {
		return nil, err
	}
	req.SetBasicAuth("relay", apiPassword)
	req.Header.Set("User-Agent", "Relay (ovpn-pin phone)")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	switch {
	case resp.StatusCode == http.StatusUnauthorized:
		return nil, fmt.Errorf("the server refused the API password")
	case resp.StatusCode == http.StatusNotFound && strings.HasPrefix(path, "config/chains/loc-"):
		return nil, fmt.Errorf("the server has no exported locations - run the installer from this version again")
	case resp.StatusCode/100 != 2:
		return nil, fmt.Errorf("the server answered %s to %s %s", resp.Status, method, strings.SplitN(path, "?", 2)[0])
	}
	return out, nil
}
