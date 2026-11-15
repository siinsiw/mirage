package core

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// The same fixtures app/configs-links-test.py reads: what both apps must
// make of each link, and which links both must refuse.
type linkFixtures struct {
	OK []struct {
		About  string         `json:"about"`
		Link   string         `json:"link"`
		Expect map[string]any `json:"expect"`
	} `json:"ok"`
	Bad []struct {
		About string `json:"about"`
		Link  string `json:"link"`
		Error string `json:"error"`
	} `json:"bad"`
}

func loadLinkFixtures(t *testing.T) linkFixtures {
	t.Helper()
	data, err := os.ReadFile(filepath.Join("..", "..", "tests", "links-fixtures.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f linkFixtures
	if err := json.Unmarshal(data, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

// asMap is the Config as JSON reads it back, plus the three things the
// fixtures also ask about - so numbers and lists compare the way the
// fixture file holds them.
func asMap(t *testing.T, c *Config) map[string]any {
	t.Helper()
	b, _ := json.Marshal(c)
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	extra, _ := json.Marshal(map[string]any{"engine": c.Engine(),
		"warnings": c.Warnings(), "label": c.Label()})
	_ = json.Unmarshal(extra, &m)
	return m
}

func TestLinkFixturesRead(t *testing.T) {
	for _, tc := range loadLinkFixtures(t).OK {
		c, err := ParseLink(tc.Link)
		if err != nil {
			t.Errorf("%s: refused: %v", tc.About, err)
			continue
		}
		got := asMap(t, c)
		for k, want := range tc.Expect {
			if !reflect.DeepEqual(got[k], want) {
				t.Errorf("%s: %s = %#v, want %#v", tc.About, k, got[k], want)
			}
		}
	}
}

func TestLinkFixturesRefused(t *testing.T) {
	for _, tc := range loadLinkFixtures(t).Bad {
		_, err := ParseLink(tc.Link)
		if err == nil {
			t.Errorf("%s: read, and should not have been", tc.About)
			continue
		}
		if !strings.Contains(strings.ToLower(err.Error()), strings.ToLower(tc.Error)) {
			t.Errorf("%s: %q does not say %q", tc.About, err.Error(), tc.Error)
		}
	}
}

func TestLinkRefusalsNeverRepeatTheSecret(t *testing.T) {
	configs, problems, err := ParseLinks("trojan://supersecret@bad_host!:443\nvless://x\nhunter2-pass", 500)
	if err != nil || len(configs) != 0 || len(problems) != 3 {
		t.Fatalf("%v %v %v", configs, problems, err)
	}
	b, _ := json.Marshal(problems)
	if strings.Contains(string(b), "supersecret") || strings.Contains(string(b), "hunter2") {
		t.Fatalf("a refusal repeated a secret: %s", b)
	}
}

func TestLinkPasteBase64AndDuplicates(t *testing.T) {
	f := loadLinkFixtures(t)
	var links []string
	for _, c := range f.OK[:5] {
		links = append(links, c.Link)
	}
	text := strings.Join(append(append(links, links[:2]...), "junk"), "\n")
	configs, problems, err := ParseLinks(text, 500)
	if err != nil || len(configs) != 5 || len(problems) != 1 || problems[0].Why != "not a link" {
		t.Fatalf("%d configs, problems %v, %v", len(configs), problems, err)
	}
	body := base64.StdEncoding.EncodeToString([]byte(strings.Join(links[:3], "\n")))
	configs, problems, err = ParseLinks(body, 500)
	if err != nil || len(configs) != 3 || len(problems) != 0 {
		t.Fatalf("base64 body: %d configs, %v, %v", len(configs), problems, err)
	}
	configs, problems, _ = ParseLinks(strings.Join(links, " "), 2)
	if len(configs) != 2 || !strings.Contains(problems[len(problems)-1].Why, "only the first 2") {
		t.Fatalf("limit: %d %v", len(configs), problems)
	}
}

func TestLinkSingboxJSON(t *testing.T) {
	data := `{"outbounds":[
	 {"type":"vless","tag":"sb-vless","server":"sb.example.com","server_port":443,
	  "uuid":"b831381d-6324-4d53-ad4f-8cda48b30811","flow":"xtls-rprx-vision",
	  "tls":{"enabled":true,"server_name":"www.microsoft.com","utls":{"enabled":true,"fingerprint":"chrome"},
	   "reality":{"enabled":true,"public_key":"jNXHt1yRo0vDuchQlIP6Z0ZvjT3KtzVI-T4E7RoLJS0","short_id":"ab"}}},
	 {"type":"shadowsocks","tag":"sb-ss","server":"203.0.113.2","server_port":8388,"method":"aes-128-gcm","password":"pw"},
	 {"type":"selector","tag":"proxy","outbounds":["sb-vless"]},{"type":"direct","tag":"direct"}]}`
	configs, problems, err := ParseLinks(data, 500)
	if err != nil || len(configs) != 2 || len(problems) != 0 {
		t.Fatalf("%v %v %v", configs, problems, err)
	}
	if configs[0].Name != "sb-vless" || configs[0].Security != "reality" || configs[0].SID != "ab" {
		t.Fatalf("%+v", configs[0])
	}
}

func TestLinkStoredConfigIsCheckedAgain(t *testing.T) {
	c, err := ParseLink(loadLinkFixtures(t).OK[0].Link)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(c)
	back, err := ParseConfig(b)
	if err != nil || back.ID != c.ID || back.Link != c.Link {
		t.Fatalf("round trip: %v %+v", err, back)
	}
	// Tampered with on disk: an address that is not one is refused again.
	var m map[string]any
	_ = json.Unmarshal(b, &m)
	m["host"] = "$(reboot).example.com"
	b, _ = json.Marshal(m)
	if _, err := ParseConfig(b); err == nil {
		t.Fatal("a stored config with a bad host was believed")
	}
}

func TestLinkNameCleaning(t *testing.T) {
	if got := CleanName("a‮b‌c\x07   d"); got != "ab‌c d" {
		t.Fatalf("%q", got)
	}
	long := strings.Repeat("ن", 100)
	if got := CleanName(long); len([]rune(got)) != 64 {
		t.Fatalf("%d", len([]rune(got)))
	}
}
