package relay

// The Configs section, as Android talks to it: servers bought or given from
// anywhere, read, tested and connected through core's sing-box Way
// (core/configs_box.go). Only strings, numbers and booleans cross gomobile,
// so lists go as JSON - the same shape the Windows app's configs/store.py
// keeps, so a config looks the same to both apps.
//
// The configs a connection is made from come back from the phone's
// encrypted storage as JSON, and each is checked again here (core.
// ParseConfig) rather than believed: storage is a file, and a file can be
// changed.

import (
	"encoding/json"
	"errors"
	"sync"
	"time"

	core "github.com/siinsiw/ovpn-pin/mobile/core"
)

// configView is a config as the screen and the storage want it: every
// field, plus the four things the screen asks about.
type configView struct {
	*core.Config
	Engine   string   `json:"engine"`
	Warnings []string `json:"warnings"`
	Label    string   `json:"label"`
	TLS      bool     `json:"tls"`
}

func viewOf(c *core.Config) configView {
	return configView{Config: c, Engine: c.Engine(), Warnings: c.Warnings(),
		Label: c.Label(), TLS: c.HasTLS()}
}

func viewsOf(set []*core.Config) []configView {
	out := make([]configView, 0, len(set))
	for _, c := range set {
		out = append(out, viewOf(c))
	}
	return out
}

func jsonOf(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"error":"could not be said"}`
	}
	return string(b)
}

func errText(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}

// ParseConfigsJSON reads a paste - links, a base64 list, or sing-box JSON:
// {"configs":[...], "problems":[{"n","what","why"}], "error":""}.
func ParseConfigsJSON(text string) string {
	set, problems, err := core.ParseLinks(text, core.LinksMaxPerSource)
	return jsonOf(map[string]any{"configs": viewsOf(set), "problems": problems,
		"error": errText(err)})
}

// configsFrom is a group read back from storage, checked again.
func configsFrom(groupJSON string) ([]*core.Config, error) {
	var items []json.RawMessage
	if err := json.Unmarshal([]byte(groupJSON), &items); err != nil {
		return nil, errors.New("the saved configs could not be read")
	}
	if len(items) > 1000 {
		items = items[:1000]
	}
	out := make([]*core.Config, 0, len(items))
	for _, it := range items {
		c, err := core.ParseConfig(it)
		if err != nil {
			continue // one bad entry is not a reason to refuse the rest
		}
		out = append(out, c)
	}
	if len(out) == 0 {
		return nil, errors.New("no usable config in this group")
	}
	return out, nil
}

// FetchSubscriptionJSON fetches one subscription, through the connection
// when one is up: {"configs","problems","info","name","plainHttp","error"}.
func FetchSubscriptionJSON(url string, timeoutMs int) string {
	set, problems, info, name, err := core.FetchSubscription(url,
		time.Duration(timeoutMs)*time.Millisecond)
	return jsonOf(map[string]any{"configs": viewsOf(set), "problems": problems,
		"info": info, "name": name, "plainHttp": core.PlainHTTP(url),
		"error": errText(err)})
}

// CheckSubscriptionURL is "" for an address that will be taken, otherwise
// why not - so the screen can say so before anything is fetched.
func CheckSubscriptionURL(url string) string {
	_, err := core.CheckSubURL(url)
	return errText(err)
}

// ConfigsProgress hears about each config as its test finishes.
type ConfigsProgress interface {
	Tested(done int, total int, resultJSON string)
}

var (
	testMu     sync.Mutex
	testCancel chan struct{}
)

// TestConfigsJSON tests every config of a group, at most width at once, and
// returns {"results":[{"id","ms","ip","country","error","said"}], "error"}.
// Blocks until done or cancelled; call it off the main thread.
func TestConfigsJSON(groupJSON string, fragment bool, width int, timeoutMs int, p ConfigsProgress) string {
	set, err := configsFrom(groupJSON)
	if err != nil {
		return jsonOf(map[string]any{"results": []any{}, "error": err.Error()})
	}
	cancel := make(chan struct{})
	testMu.Lock()
	if testCancel != nil {
		testMu.Unlock()
		return jsonOf(map[string]any{"results": []any{}, "error": "a test is already running"})
	}
	testCancel = cancel
	testMu.Unlock()
	defer func() {
		testMu.Lock()
		testCancel = nil
		testMu.Unlock()
	}()
	results, err := core.ProbeConfigs(set, fragment, width,
		time.Duration(timeoutMs)*time.Millisecond, cancel,
		func(done, total int, r core.ProbeResult) {
			if p != nil {
				p.Tested(done, total, jsonOf(r))
			}
		})
	if results == nil {
		results = []core.ProbeResult{}
	}
	return jsonOf(map[string]any{"results": results, "error": errText(err)})
}

// CancelConfigsTest stops the configs not yet started; the ones under way
// finish.
func CancelConfigsTest() {
	testMu.Lock()
	defer testMu.Unlock()
	if testCancel != nil {
		close(testCancel)
		testCancel = nil
	}
}

// StartConfigsTunnel carries the phone's traffic through a group of configs
// - the same tun, stack, DNS and split tunnelling as the other two ways;
// only the Way underneath differs.
//
// fd is handed over exactly as for StartServerTunnel. If sing-box will not
// start for this group the error comes back and the descriptor is left
// alone: the caller closes it (ParcelFileDescriptor.adoptFd(fd).close()), as
// it does for the other two ways - closing it on both sides is what
// Android's fdsan ends the process for.
func (c *Client) StartConfigsTunnel(fd int, mtu int, groupJSON, selected string, fastest, fragment bool) error {
	set, err := configsFrom(groupJSON)
	if err != nil {
		return err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.tunnel != nil {
		return errTunnelUp
	}
	w, err := core.NewConfigsWay(set, selected, fastest, fragment)
	if err != nil {
		return err
	}
	t, err := core.StartTunnel(fd, mtu, w)
	if err != nil {
		w.Close()
		return err
	}
	t.SetSplit(c.split)
	c.tunnel = t
	c.server = w
	core.SetLive(w)
	return nil
}

// SelectConfig moves new connections to another config of the running
// group, without taking anything down. False when no group is up, auto is
// choosing, or the config is not in it - the phone reconnects instead.
func (c *Client) SelectConfig(id string) bool {
	c.mu.Lock()
	w, _ := c.server.(*core.ConfigsWay)
	c.mu.Unlock()
	return w != nil && w.Select(id)
}

// ConfigNow is the id of the config carrying - with auto, the one it picked
// - or "" when no group is up.
func (c *Client) ConfigNow() string {
	c.mu.Lock()
	w, _ := c.server.(*core.ConfigsWay)
	c.mu.Unlock()
	if w == nil {
		return ""
	}
	return w.Now()
}
