package relay

import (
	"strings"
	"testing"
)

// The bridge refuses to share without a password before it looks at
// anything else - no tunnel needed to be told so.
func TestStartLANShareNeedsAPassword(t *testing.T) {
	c := NewClient("", "")
	for _, pw := range []string{"", "short", "1234567"} {
		if _, err := c.StartLANShare(8877, pw); err == nil || !strings.Contains(err.Error(), "password") {
			t.Errorf("StartLANShare with %q: %v", pw, err)
		}
	}
	// A good password gets as far as the next check: there is no tunnel.
	if _, err := c.StartLANShare(8877, "long-enough"); err == nil || !strings.Contains(err.Error(), "tunnel") {
		t.Errorf("with a password and no tunnel: %v", err)
	}
	if got := c.LANShareJSON(); !strings.Contains(got, `"on":false`) {
		t.Errorf("LANShareJSON: %s", got)
	}
}
