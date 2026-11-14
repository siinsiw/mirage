package core

// Latency tests: how long a real page takes to come back through each
// config, from this line - the phone's copy of app/configs/probe.py.
//
// Not a ping to the server's port: for a server behind a CDN that measures
// the CDN, and for any server it says nothing about whether the far end
// carries anything. Each config is asked for the trace page this core
// already uses (1.1.1.1/cdn-cgi/trace) over TLS, through the config, and the
// time is from the first byte sent to the page read. The page also says the
// country it was asked from - the country the config really comes out in.
//
// Straight from this line, never through a connection that is up: the app
// is left out of its own VPN, and the question is how this config does from
// here. Nothing runs in the background; a test is something the person
// asked for, and at most four run at once (weak phones).

import (
	"bufio"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"context"
	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

// ProbeResult is one config's answer. MS is 0 when it failed.
type ProbeResult struct {
	ID      string `json:"id"`
	MS      int    `json:"ms"`
	IP      string `json:"ip,omitempty"`
	Country string `json:"country,omitempty"`
	Error   string `json:"error,omitempty"`
	Said    string `json:"said,omitempty"`
}

// What is asked through each config. Variables so the tests can point them
// at a server of their own.
var (
	cfgProbeTarget = "1.1.1.1:443"
	cfgProbeSNI    = "1.1.1.1"
	cfgProbeRoots  *x509.CertPool
)

// classify turns sing-box's words into a short reason for the screen.
func classify(said string) string {
	m := strings.ToLower(said)
	switch {
	case strings.Contains(m, "x509") || strings.Contains(m, "certificate"):
		return "cert"
	case strings.Contains(m, "timeout") || strings.Contains(m, "deadline") || strings.Contains(m, "timed out"):
		return "timeout"
	case strings.Contains(m, "refused"):
		return "refused"
	case strings.Contains(m, "no such host") || strings.Contains(m, "lookup"):
		return "dns"
	case strings.Contains(m, "eof") || strings.Contains(m, "reset") || strings.Contains(m, "closed"):
		return "closed"
	}
	return "failed"
}

func probeThrough(ob adapter.Outbound, timeout time.Duration) (ms int, ip, country string, err error) {
	began := time.Now()
	conn, err := dialed(timeout, func() (net.Conn, error) {
		return ob.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddr(cfgProbeTarget))
	})
	if err != nil {
		return 0, "", "", err
	}
	defer conn.Close()
	_ = conn.SetDeadline(began.Add(timeout))
	tc := tls.Client(conn, &tls.Config{ServerName: cfgProbeSNI, RootCAs: cfgProbeRoots})
	if err := tc.Handshake(); err != nil {
		return 0, "", "", err
	}
	host := cfgProbeSNI
	if _, err := io.WriteString(tc, "GET /cdn-cgi/trace HTTP/1.1\r\nHost: "+host+
		"\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n"); err != nil {
		return 0, "", "", err
	}
	r := bufio.NewReader(io.LimitReader(tc, 16384))
	status, err := r.ReadString('\n')
	if err != nil {
		return 0, "", "", err
	}
	if !strings.Contains(status, " 200") {
		return 0, "", "", errors.New("the trace page did not answer")
	}
	for {
		line, err := r.ReadString('\n')
		line = strings.TrimSpace(line)
		if k, v, ok := strings.Cut(line, "="); ok && !strings.Contains(k, " ") {
			switch k {
			case "ip":
				ip = v
			case "loc":
				country = strings.ToLower(v)
				if len(country) > 2 {
					country = country[:2]
				}
			}
		}
		if country != "" || err != nil {
			break
		}
	}
	ms = int(time.Since(began).Milliseconds())
	if ms < 1 {
		ms = 1
	}
	return ms, ip, country, nil
}

// ProbeConfigs tests every config of set, at most width at once, calling
// progress after each. A closed cancel stops the ones not started yet.
func ProbeConfigs(set []*Config, fragment bool, width int, timeout time.Duration,
	cancel <-chan struct{}, progress func(done, total int, r ProbeResult)) ([]ProbeResult, error) {
	if width < 1 {
		width = 1
	}
	var results []ProbeResult
	var mu sync.Mutex
	done := 0
	note := func(r ProbeResult) {
		mu.Lock()
		results = append(results, r)
		done++
		d := done
		mu.Unlock()
		if progress != nil {
			progress(d, len(set), r)
		}
	}
	var carried []*Config
	for _, c := range set {
		if c.Engine() == "xray" {
			note(ProbeResult{ID: c.ID, Error: "xray-missing"})
			continue
		}
		carried = append(carried, c)
	}
	if len(carried) == 0 {
		return results, nil
	}
	data, err := boxJSON(carried, "", false, fragment)
	if err != nil {
		return results, err
	}
	b, err := startBox(data)
	if err != nil {
		return results, err
	}
	defer b.Close()

	slots := make(chan struct{}, width)
	var wg sync.WaitGroup
	for _, c := range carried {
		select {
		case <-cancel:
			note(ProbeResult{ID: c.ID, Error: "cancelled"})
			continue
		case slots <- struct{}{}:
		}
		wg.Add(1)
		go func(c *Config) {
			defer wg.Done()
			defer func() { <-slots }()
			ob, ok := b.Outbound().Outbound(configTag(c))
			if !ok {
				note(ProbeResult{ID: c.ID, Error: "failed", Said: "sing-box did not make it"})
				return
			}
			ms, ip, country, err := probeThrough(ob, timeout)
			if err != nil {
				said := err.Error()
				if len(said) > 160 {
					said = said[len(said)-160:]
				}
				note(ProbeResult{ID: c.ID, Error: classify(err.Error()), Said: said})
				return
			}
			note(ProbeResult{ID: c.ID, MS: ms, IP: ip, Country: country})
		}(c)
	}
	wg.Wait()
	return results, nil
}
