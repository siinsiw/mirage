package core

// How fast a way out carries an upload, measured rather than assumed.
//
// From a server in Iran, the line throttles upload to foreign exits: most
// carried 10-20 KB/s up while downloading at megabytes, and a few in the same
// city carried the full rate (measured 2026-10-31 from 203.0.113.10: ch-017
// 16 KB/s, ch-019 3 MB/s). That looked like a per-IP choice. Measured again
// on 2026-11-12 from a VPS in Iran it turned out to be mostly about the
// handshake: TLS to a Windscribe exit without SNI ran at 0-17 KB/s on every
// exit, and per connection (four parallel uploads each got 8-12 KB/s); with
// the exit's real name as SNI and its certificate verified, six of seven
// exits ran at 1,237-1,915 KB/s against a 3.4-4.1 MB/s uplink; an unrelated
// decoy name did not help (4-17 KB/s). One exit (ch-020) stayed at 12-17 KB/s
// whatever was sent, so a bad exit still exists, and Surfshark's exits did
// not complete TLS from that server at all. Which exit is which still does
// not show in a ping or a download, so server + exit asks each candidate to
// carry a small upload and keeps the first that does.

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"net"
	"net/http"
	"time"
)

// uploadTarget swallows a POST and answers once it has all of it. Cloudflare's
// own speed test endpoint, by name: the request goes through the way, so the
// lookup is the far end's and not this line's.
const uploadTarget = "https://speed.cloudflare.com/__up"

// UploadRate sends size bytes through w and returns how fast they went, in
// bytes a second, timed to the far end's answer - which only comes once every
// byte has arrived, so buffers along the way cannot flatter a slow exit. A
// throttled exit does not fail, it crawls: past timeout it is an error.
func UploadRate(w Way, size int, timeout time.Duration) (float64, error) {
	body := make([]byte, size)
	_, _ = rand.Read(body)

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	client := &http.Client{Transport: &http.Transport{
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
		DisableKeepAlives: true,
	}}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, uploadTarget, bytes.NewReader(body))
	if err != nil {
		return 0, err
	}
	req.Header.Set("Content-Type", "application/octet-stream")

	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	resp.Body.Close()
	if resp.StatusCode/100 != 2 {
		return 0, errors.New("the upload test answered " + resp.Status)
	}
	took := time.Since(start)
	if took <= 0 {
		took = time.Millisecond
	}
	return float64(size) / took.Seconds(), nil
}
