package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

// ReleasesLatestURL is where GitHub says which release is the newest.
const ReleasesLatestURL = "https://api.github.com/repos/siinsiw/ovpn-pin/releases/latest"

// LatestRelease is the tag of the newest published release, asked through w -
// the tunnel that is up - rather than past it: "is there a newer version" is
// a question the line underneath has no need to see being asked. Nothing is
// sent but the request itself; nothing is downloaded or installed.
//
// Only ever on request: no part of this package calls it on its own.
func LatestRelease(ctx context.Context, w Way, timeout time.Duration) (string, error) {
	if w == nil {
		return "", errors.New("no way out to ask through")
	}

	client := &http.Client{
		Timeout: timeout,
		Transport: &http.Transport{
			Proxy: nil,
			DialContext: func(_ context.Context, _, addr string) (net.Conn, error) {
				conn, spare, err := w.Open(addr, timeout)
				if err != nil {
					return nil, err
				}
				if len(spare) > 0 {
					conn.Close()
					return nil, errors.New("the exit spoke before it was asked to")
				}
				return conn, nil
			},
			TLSHandshakeTimeout: timeout,
			DisableKeepAlives:   true,
		},
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, ReleasesLatestURL, nil)
	if err != nil {
		return "", err
	}
	// GitHub refuses a request with no User-Agent.
	req.Header.Set("User-Agent", "relay-update-check")
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("GitHub answered %s", resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return "", err
	}
	return releaseTag(body)
}

// releaseTag reads tag_name out of GitHub's answer for one release.
func releaseTag(body []byte) (string, error) {
	var r struct {
		Tag string `json:"tag_name"`
	}
	if err := json.Unmarshal(body, &r); err != nil {
		return "", err
	}
	tag := strings.TrimSpace(r.Tag)
	if tag == "" {
		return "", errors.New("the release has no tag")
	}
	return tag, nil
}
