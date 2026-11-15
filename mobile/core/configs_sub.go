package core

// Fetching a subscription - the phone's copy of app/configs/subs.py: only
// when asked, through the connection that is up when there is one, and read
// as untrusted from the first byte.
//
// The address is the seller's key to the list, so it is checked like any
// other input and never logged. The answer is capped at 2 MB and three
// redirects, and a redirect from https to plain http is refused - the one
// way a line could swap the list for its own without the person typing
// http. Plain http itself is allowed (some panels serve nothing else); the
// screen marks it.

import (
	"crypto/tls"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	subMaxBody      = 2 * 1024 * 1024
	subMaxURL       = 2048
	subMaxRedirects = 3
)

// SubInfo is subscription-userinfo, as numbers: bytes, and a Unix time.
type SubInfo struct {
	Upload   int64 `json:"upload,omitempty"`
	Download int64 `json:"download,omitempty"`
	Total    int64 `json:"total,omitempty"`
	Expire   int64 `json:"expire,omitempty"`
}

var reWWW = regexp.MustCompile(`^www\.`)

// CheckSubURL is the address, checked - or why it is refused.
func CheckSubURL(raw string) (string, error) {
	s := strings.TrimSpace(raw)
	if s == "" || len(s) > subMaxURL {
		return "", bad("that is not a subscription address")
	}
	for _, r := range s {
		if r < 33 {
			return "", bad("the address has spaces or control characters in it")
		}
	}
	u, err := url.Parse(s)
	if err != nil {
		return "", bad("that is not a subscription address")
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return "", bad("a subscription address starts with https:// or http://")
	}
	if u.User != nil {
		return "", bad("a subscription address with a password in it is not taken")
	}
	if _, err := hostOK(u.Hostname(), "subscription"); err != nil {
		return "", bad("the subscription host is not a valid name or address")
	}
	if p := u.Port(); p != "" {
		if n, err := strconv.Atoi(p); err != nil || n < 1 || n > 65535 {
			return "", bad("the subscription host is not a valid name or address")
		}
	}
	return s, nil
}

func subUserinfo(v string) *SubInfo {
	if v == "" {
		return nil
	}
	info := &SubInfo{}
	seen := false
	for _, part := range strings.Split(v, ";") {
		k, val, _ := strings.Cut(strings.TrimSpace(part), "=")
		f, err := strconv.ParseFloat(strings.TrimSpace(val), 64)
		if err != nil || f < 0 {
			continue
		}
		n := int64(f)
		switch strings.ToLower(strings.TrimSpace(k)) {
		case "upload":
			info.Upload, seen = n, true
		case "download":
			info.Download, seen = n, true
		case "total":
			info.Total, seen = n, true
		case "expire":
			info.Expire, seen = n, true
		}
	}
	if !seen {
		return nil
	}
	return info
}

func subTitle(v string) string {
	v = strings.TrimSpace(v)
	if v == "" {
		return ""
	}
	if strings.HasPrefix(strings.ToLower(v), "base64:") {
		d := b64(v[7:])
		if d == nil {
			return ""
		}
		v = strings.ToValidUTF8(string(d), "�")
	}
	return CleanName(unquote(v, false))
}

// FetchSubscription fetches and reads one subscription. Through whatever is
// carrying when something is (LiveWay), otherwise straight out.
func FetchSubscription(raw string, timeout time.Duration) ([]*Config, []LinkProblem, *SubInfo, string, error) {
	return fetchWith(raw, timeout, nil)
}

// fetchWith is FetchSubscription with the TLS settings given - the tests'
// own certificate authority; nil everywhere else.
func fetchWith(raw string, timeout time.Duration, tlsConf *tls.Config) ([]*Config, []LinkProblem, *SubInfo, string, error) {
	addr, err := CheckSubURL(raw)
	if err != nil {
		return nil, nil, nil, "", err
	}
	var client *http.Client
	if w := LiveWay(); w != nil {
		client = through(w, timeout)
	} else {
		d := net.Dialer{Timeout: timeout, Control: dialControl}
		client = &http.Client{Timeout: timeout,
			Transport: &http.Transport{Proxy: nil, DialContext: d.DialContext,
				TLSClientConfig: tlsConf}}
	}
	client.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		if len(via) > subMaxRedirects {
			return bad("the subscription redirected too many times")
		}
		if _, err := CheckSubURL(req.URL.String()); err != nil {
			return err
		}
		if via[len(via)-1].URL.Scheme == "https" && req.URL.Scheme != "https" {
			return bad("the subscription redirected from https to plain http, which is refused")
		}
		return nil
	}
	req, _ := http.NewRequest(http.MethodGet, addr, nil)
	req.Header.Set("User-Agent", "Mirage")
	req.Header.Set("Accept", "*/*")
	resp, err := client.Do(req)
	if err != nil {
		var le *ErrLink
		if errors.As(err, &le) {
			return nil, nil, nil, "", le
		}
		// The address is the seller's key: the error is said without it.
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err
		}
		return nil, nil, nil, "", bad("the subscription could not be reached (%s)", trunc(err.Error(), 120))
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, nil, nil, "", bad("the subscription answered %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, subMaxBody+1))
	if err != nil {
		return nil, nil, nil, "", bad("the subscription stopped halfway (%s)", trunc(err.Error(), 80))
	}
	if len(body) > subMaxBody {
		return nil, nil, nil, "", bad("the subscription sent more than 2 MB")
	}
	configs, problems, err := ParseLinks(strings.ToValidUTF8(string(body), "�"), LinksMaxPerSource)
	if err != nil {
		return nil, nil, nil, "", bad("nothing usable in the subscription (%s)", err)
	}
	name := subTitle(resp.Header.Get("profile-title"))
	if name == "" {
		if u, err := url.Parse(addr); err == nil {
			name = reWWW.ReplaceAllString(u.Hostname(), "")
		}
	}
	return configs, problems, subUserinfo(resp.Header.Get("subscription-userinfo")), name, nil
}

// PlainHTTP is whether the address is plain http (marked on the screen).
func PlainHTTP(addr string) bool {
	u, err := url.Parse(addr)
	return err == nil && u.Scheme == "http"
}
