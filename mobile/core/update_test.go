package core

import (
	"context"
	"testing"
	"time"
)

func TestReleaseTag(t *testing.T) {
	got, err := releaseTag([]byte(`{"url":"https://api.github.com/x","tag_name":" v0.4.1 ","name":"Relay 0.4.1","assets":[]}`))
	if err != nil || got != "v0.4.1" {
		t.Fatalf("releaseTag = %q, %v", got, err)
	}
	for _, bad := range []string{`{}`, `{"tag_name":"  "}`, `{"message":"Not Found"}`, `not json`, ``} {
		if got, err := releaseTag([]byte(bad)); err == nil {
			t.Fatalf("releaseTag(%q) = %q, want an error", bad, got)
		}
	}
}

// With nothing carrying, nothing is asked: the question never goes out by the
// bare line from here.
func TestLatestReleaseNeedsAWay(t *testing.T) {
	if got, err := LatestRelease(context.Background(), nil, time.Second); err == nil {
		t.Fatalf("LatestRelease with no way out = %q, want an error", got)
	}
}
