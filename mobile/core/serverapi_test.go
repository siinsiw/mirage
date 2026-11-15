package core

import (
	"context"
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

// A real server reached by IP, through the real line - skipped unless given:
//
//	MIRAGE_TEST_SERVER=203.0.113.10 MIRAGE_TEST_PASSWORD=... MIRAGE_TEST_API=... go test -run ServerByAddress -v
func TestServerByAddress(t *testing.T) {
	host := os.Getenv("MIRAGE_TEST_SERVER")
	if host == "" {
		t.Skip("MIRAGE_TEST_SERVER not set")
	}
	pw := os.Getenv("MIRAGE_TEST_PASSWORD")

	// First contact: no pin yet, so nothing is dialled - the certificate is
	// looked at on a bare handshake and handed back as the pin (here the
	// test stands in for the person who confirms it).
	srv := NewTunnelServer(host, pw, nil, ModeSingle)
	if _, err := SeenAsThrough(srv, 20*time.Second); err == nil || !strings.Contains(err.Error(), ErrNoPin.Error()) {
		t.Fatalf("an address without a pin was dialled: %v", err)
	}
	pctx, pcancel := context.WithTimeout(context.Background(), 20*time.Second)
	pin, err := srv.PeekPin(pctx, 20*time.Second)
	pcancel()
	if err != nil || len(pin) != 64 {
		t.Fatalf("no pin seen: %q, %v", pin, err)
	}
	srv.Pin = pin
	out, err := SeenAsThrough(srv, 20*time.Second)
	srv.Close()
	if err != nil {
		t.Fatalf("through %s: %v", host, err)
	}
	t.Logf("seen as %s, pin %s", out, pin)

	// Pinned: the same certificate passes, any other is refused.
	ok := NewTunnelServer(host, pw, nil, ModeSingle)
	ok.Pin = pin
	if _, err := SeenAsThrough(ok, 20*time.Second); err != nil {
		t.Fatalf("pinned: %v", err)
	}
	ok.Close()
	bad := NewTunnelServer(host, pw, nil, ModeSingle)
	bad.Pin = strings.Repeat("0", 64)
	if _, err := SeenAsThrough(bad, 20*time.Second); err == nil || !errors.Is(err, ErrPinChanged) && !strings.Contains(err.Error(), ErrPinChanged.Error()) {
		t.Fatalf("a wrong pin was not refused: %v", err)
	}
	bad.Close()

	// The API answers with the right password and refuses a wrong one.
	if api := os.Getenv("MIRAGE_TEST_API"); api != "" {
		if err := SetExit(host, 0, "wrong", pin, ExitHop{IP: "146.70.194.221", User: "CHANGEME", Password: "CHANGEME"}, 10*time.Second); err == nil {
			t.Fatal("a wrong API password was accepted")
		}
		if err := SetExit(host, 0, api, pin, ExitHop{IP: "146.70.194.221", User: "CHANGEME", Password: "CHANGEME"}, 10*time.Second); err != nil {
			t.Fatalf("set exit: %v", err)
		}
	}
}

// The upload test tells a throttled exit from a free one, through the server:
//
//	MIRAGE_TEST_SERVER=… MIRAGE_TEST_PASSWORD=… MIRAGE_TEST_API=… MIRAGE_TEST_PIN=…
//	MIRAGE_TEST_EXITS=141.255.162.194,31.7.62.50 MIRAGE_TEST_EXIT_USER=… MIRAGE_TEST_EXIT_PASS=…
//	go test -run UploadThroughExits -v
func TestUploadThroughExits(t *testing.T) {
	host, exits := os.Getenv("MIRAGE_TEST_SERVER"), os.Getenv("MIRAGE_TEST_EXITS")
	if host == "" || exits == "" {
		t.Skip("MIRAGE_TEST_SERVER or MIRAGE_TEST_EXITS not set")
	}
	for _, ip := range strings.Split(exits, ",") {
		if err := SetExit(host, 0, os.Getenv("MIRAGE_TEST_API"), os.Getenv("MIRAGE_TEST_PIN"),
			ExitHop{IP: ip, User: os.Getenv("MIRAGE_TEST_EXIT_USER"), Password: os.Getenv("MIRAGE_TEST_EXIT_PASS")}, 15*time.Second); err != nil {
			t.Fatalf("set %s: %v", ip, err)
		}
		srv := NewTunnelServer(host, os.Getenv("MIRAGE_TEST_PASSWORD"), nil, ModeMulti)
		srv.Pin = os.Getenv("MIRAGE_TEST_PIN")
		rate, err := UploadRate(srv, 256<<10, 4*time.Second)
		srv.Close()
		t.Logf("%-16s up %.0f KB/s (err %v)", ip, rate/1024, err)
	}
}
