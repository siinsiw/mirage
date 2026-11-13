package core

import (
	"context"
	"testing"
	"time"
)

func TestNewestAndroidTag(t *testing.T) {
	list := `[
		{"tag_name":"desktop-v9.0.0"},
		{"tag_name":"android-v0.10.0","draft":true},
		{"tag_name":"android-v0.9.5","prerelease":true},
		{"tag_name":" android-v0.4.1 "},
		{"tag_name":"android-v0.4.0"},
		{"tag_name":"android-vnext"}
	]`
	got, err := newestAndroidTag([]byte(list))
	if err != nil || got != "android-v0.4.1" {
		t.Fatalf("newestAndroidTag = %q, %v; want android-v0.4.1 (Windows, drafts and pre-releases left out)", got, err)
	}
	if got, err := newestAndroidTag([]byte(`[{"tag_name":"android-v0.9.0"},{"tag_name":"android-v0.10.0"}]`)); err != nil || got != "android-v0.10.0" {
		t.Fatalf("ten after nine: got %q, %v", got, err)
	}
	for _, bad := range []string{`[]`, `[{"tag_name":"desktop-v1.0"}]`, `{"message":"Not Found"}`, `not json`, ``} {
		if got, err := newestAndroidTag([]byte(bad)); err == nil {
			t.Fatalf("newestAndroidTag(%q) = %q, want an error", bad, got)
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
