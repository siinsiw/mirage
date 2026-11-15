package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ReleasesURL is GitHub's list of this repository's releases, newest first.
// The list and not releases/latest: Windows is released from the same
// repository (desktop-v* tags), and "the newest release" may well be one of
// those - a version number the Android app has nothing to compare with.
const ReleasesURL = "https://api.github.com/repos/siinsiw/ovpn-pin/releases?per_page=30"

// AndroidTagPrefix is how the Android app's own release tags begin.
const AndroidTagPrefix = "android-v"

// LatestRelease is the tag of the newest published Android release, asked through w -
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

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, ReleasesURL, nil)
	if err != nil {
		return "", err
	}
	// GitHub refuses a request with no User-Agent.
	req.Header.Set("User-Agent", "mirage-update-check")
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
	return newestAndroidTag(body)
}

// newestAndroidTag is the highest android-v* tag among the published (not
// draft, not pre-release) releases in GitHub's list.
func newestAndroidTag(body []byte) (string, error) {
	var list []struct {
		Tag        string `json:"tag_name"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
	}
	if err := json.Unmarshal(body, &list); err != nil {
		return "", err
	}
	best, bestV := "", []int(nil)
	for _, r := range list {
		tag := strings.TrimSpace(r.Tag)
		if r.Draft || r.Prerelease || !strings.HasPrefix(tag, AndroidTagPrefix) {
			continue
		}
		v := versionNumbers(strings.TrimPrefix(tag, AndroidTagPrefix))
		if v == nil {
			continue
		}
		if bestV == nil || laterVersion(v, bestV) {
			best, bestV = tag, v
		}
	}
	if best == "" {
		return "", errors.New("GitHub lists no Android release")
	}
	return best, nil
}

// versionNumbers is "1.2.3" as [1 2 3]; nil when it is not dotted numbers.
func versionNumbers(s string) []int {
	parts := strings.Split(s, ".")
	out := make([]int, 0, len(parts))
	for _, p := range parts {
		n, err := strconv.Atoi(p)
		if err != nil || n < 0 {
			return nil
		}
		out = append(out, n)
	}
	return out
}

// laterVersion: a after b, number by number, a missing number counting as 0.
func laterVersion(a, b []int) bool {
	for i := 0; i < len(a) || i < len(b); i++ {
		x, y := 0, 0
		if i < len(a) {
			x = a[i]
		}
		if i < len(b) {
			y = b[i]
		}
		if x != y {
			return x > y
		}
	}
	return false
}
