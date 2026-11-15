package core

// Links into one plain shape, checked field by field - the phone's copy of
// app/configs/links.py, held to the same answers by tests/links-fixtures.json
// (links_test.go reads it; so does app/configs-links-test.py).
//
// Everything here arrives from a place nobody vouches for: a Telegram
// channel, a seller's subscription, a QR code photographed off a screen. So
// every field is checked against what it can be, and what comes out is a
// small struct of plain values that the sing-box configuration is built from
// with encoding/json. Link text never reaches the middle of a string that
// becomes configuration.

import (
	"crypto/sha1"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"golang.org/x/net/idna"
)

const (
	LinksMaxText      = 512 * 1024 // a whole paste, or a whole subscription body
	LinksMaxLink      = 4096       // one link
	LinksMaxPerSource = 500        // links kept from one paste or subscription
	linksMaxName      = 64
	linksMaxPath      = 1024
	linksMaxSecret    = 256
	linksMaxExtra     = 4096
	linksMaxQueryKeys = 64
)

// Shown to the person, so in words. The keys are what the screen tells apart.
const (
	WarnPlain    = "plain"    // nothing encrypts it on the way to the server
	WarnNoTLS    = "notls"    // encrypted, but no certificate proves the server
	WarnInsecure = "insecure" // the link asks for the certificate check off
)

// Config is one server, as every reader leaves it. The json names are the
// Windows app's, so the shared fixtures read the same on both.
type Config struct {
	ID            string         `json:"id"`
	Name          string         `json:"name"`
	Proto         string         `json:"proto"`
	Host          string         `json:"host"`
	Port          int            `json:"port"`
	UUID          string         `json:"uuid"`
	Password      string         `json:"password"`
	Method        string         `json:"method"`
	AlterID       int            `json:"alter_id"`
	Cipher        string         `json:"cipher"`
	Flow          string         `json:"flow"`
	Encryption    string         `json:"encryption"`
	Net           string         `json:"net"`
	Path          string         `json:"path"`
	HostHeader    string         `json:"host_header"`
	Service       string         `json:"service"`
	Mode          string         `json:"mode"`
	Header        string         `json:"header"`
	Extra         map[string]any `json:"extra"`
	ED            int            `json:"ed"`
	Security      string         `json:"security"`
	SNI           string         `json:"sni"`
	ALPN          []string       `json:"alpn"`
	FP            string         `json:"fp"`
	PBK           string         `json:"pbk"`
	SID           string         `json:"sid"`
	SPX           string         `json:"spx"`
	Insecure      bool           `json:"insecure"`
	Obfs          string         `json:"obfs"`
	ObfsPassword  string         `json:"obfs_password"`
	Congestion    string         `json:"congestion"`
	UDPRelay      string         `json:"udp_relay"`
	Link          string         `json:"link,omitempty"`
	AllowInsecure bool           `json:"allow_insecure"`
}

// LinkProblem is one line that was not taken, and why. Never the link
// itself past its scheme: a refused link still carries a password.
type LinkProblem struct {
	N    int    `json:"n"`
	What string `json:"what"`
	Why  string `json:"why"`
}

// ErrLink is a link that cannot be used, with the reason in words.
type ErrLink struct{ Why string }

func (e *ErrLink) Error() string { return e.Why }

func bad(format string, a ...any) error { return &ErrLink{fmt.Sprintf(format, a...)} }

var (
	linkSchemes = map[string]string{"vless": "vless", "vmess": "vmess",
		"trojan": "trojan", "ss": "ss", "hysteria2": "hysteria2",
		"hy2": "hysteria2", "tuic": "tuic"}
	linkNets = map[string]string{"": "tcp", "tcp": "tcp", "raw": "tcp",
		"ws": "ws", "websocket": "ws", "grpc": "grpc", "gun": "grpc",
		"httpupgrade": "httpupgrade", "http": "http", "h2": "http",
		"xhttp": "xhttp", "splithttp": "xhttp"}
	linkNetsRefused = map[string]string{"kcp": "mKCP", "mkcp": "mKCP",
		"quic": "QUIC transport", "domainsocket": "domain sockets",
		"hysteria": "Hysteria 1"}
	linkFingerprints = setOf("chrome", "firefox", "safari", "ios", "android",
		"edge", "360", "qq", "random", "randomized")
	linkALPNs        = []string{"h2", "http/1.1", "h3"}
	linkVmessCiphers = setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")
	linkSSMethods    = setOf("aes-128-gcm", "aes-192-gcm", "aes-256-gcm",
		"chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
		"2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
		"2022-blake3-chacha20-poly1305")
	linkSSAliases = map[string]string{"chacha20-poly1305": "chacha20-ietf-poly1305",
		"xchacha20-poly1305": "xchacha20-ietf-poly1305"}
	linkXHTTPModes = setOf("auto", "packet-up", "stream-up", "stream-one")

	reLabel      = regexp.MustCompile(`^[a-z0-9_](?:[a-z0-9_-]{0,61}[a-z0-9_])?$`)
	reUUID       = regexp.MustCompile(`(?i)^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$`)
	rePBK        = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
	reSID        = regexp.MustCompile(`^[0-9a-fA-F]{0,16}$`)
	reService    = regexp.MustCompile(`^[A-Za-z0-9._/-]{0,256}$`)
	reEncryption = regexp.MustCompile(`^[A-Za-z0-9._-]+$`) // and at most 1024 long
	reB64        = regexp.MustCompile(`^[A-Za-z0-9+/_=-]+$`)
	reHostPort6  = regexp.MustCompile(`^\[([^\]]+)\]:(.+)$`)
	reHostPort   = regexp.MustCompile(`^([^:\[\]]+):(.+)$`)
	rePorts      = regexp.MustCompile(`^(\d{1,5})(?:[,-]\d{1,5})*$`)
	reScheme     = regexp.MustCompile(`^[a-z0-9+.-]+$`)
)

func setOf(items ...string) map[string]bool {
	m := make(map[string]bool, len(items))
	for _, s := range items {
		m[s] = true
	}
	return m
}

// -- small checks --------------------------------------------------------------

// Direction marks and overrides: a name carrying them can show as something
// it is not. Zero-width non-joiner and joiner are kept - Persian spelling
// needs the first, emoji the second.
func isBidi(r rune) bool {
	switch r {
	case '‎', '‏', '‪', '‫', '‬', '‭', '‮',
		'⁦', '⁧', '⁨', '⁩', '؜':
		return true
	}
	return false
}

func assigned(r rune) bool {
	return unicode.In(r, unicode.L, unicode.M, unicode.N, unicode.P,
		unicode.S, unicode.Z, unicode.Cc, unicode.Cf, unicode.Co)
}

// CleanName is a name fit to show: no control or direction characters, at
// most 64 long.
func CleanName(text string) string {
	var b strings.Builder
	n := 0
	for _, r := range text {
		if n >= linksMaxName*4 {
			break
		}
		n++
		if r == '‌' || r == '‍' {
			b.WriteRune(r)
			continue
		}
		if isBidi(r) || r == utf8.RuneError || unicode.In(r, unicode.Cc, unicode.Cf, unicode.Co) ||
			!assigned(r) {
			continue
		}
		if unicode.In(r, unicode.Z) {
			r = ' '
		}
		b.WriteRune(r)
	}
	out := strings.Join(strings.Fields(b.String()), " ")
	if utf8.RuneCountInString(out) > linksMaxName {
		out = string([]rune(out)[:linksMaxName])
	}
	return strings.TrimSpace(out)
}

func hostOK(text, what string) (string, error) {
	s := strings.TrimSpace(text)
	if strings.HasPrefix(s, "[") && strings.HasSuffix(s, "]") {
		s = s[1 : len(s)-1]
	}
	if s == "" {
		return "", bad("no %s address", what)
	}
	if a, err := netip.ParseAddr(s); err == nil {
		return a.String(), nil
	}
	s = strings.ToLower(strings.TrimRight(s, "."))
	for _, r := range s {
		if r > 127 {
			ascii, err := idna.Lookup.ToASCII(s)
			if err != nil {
				return "", bad("the %s name is not a valid name", what)
			}
			s = ascii
			break
		}
	}
	labels := strings.Split(s, ".")
	valid := len(s) <= 253 && len(labels) >= 2
	for _, l := range labels {
		if !reLabel.MatchString(l) {
			valid = false
		}
	}
	if valid {
		if _, err := strconv.Atoi(labels[len(labels)-1]); err == nil {
			valid = false
		}
	}
	if !valid {
		return "", bad("the %s name is not a valid name", what)
	}
	return s, nil
}

func isAddress(host string) bool {
	_, err := netip.ParseAddr(host)
	return err == nil
}

func portOK(value string) (int, error) {
	p, err := strconv.Atoi(strings.TrimSpace(value))
	if err != nil {
		return 0, bad("the port is not a number")
	}
	if p < 1 || p > 65535 {
		return 0, bad("the port is out of range")
	}
	return p, nil
}

// uuidOK is an id, as a UUID. Xray also takes a short word in its place and
// maps it to a UUID the same way everywhere (version 5 under the all-zero
// namespace), so that is done here too rather than refused.
func uuidOK(text string) (string, error) {
	s := strings.TrimSpace(text)
	if reUUID.MatchString(s) {
		h := strings.ToLower(strings.ReplaceAll(s, "-", ""))
		return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32], nil
	}
	if n := len(s); n >= 1 && n <= 30 {
		ok := true
		for i := 0; i < n; i++ {
			if s[i] < 33 || s[i] >= 127 {
				ok = false
			}
		}
		if ok {
			sum := sha1.Sum(append(make([]byte, 16), s...))
			u := sum[:16]
			u[6] = (u[6] & 0x0f) | 0x50
			u[8] = (u[8] & 0x3f) | 0x80
			h := hex.EncodeToString(u)
			return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:32], nil
		}
	}
	return "", bad("the id is not a UUID")
}

func hasControl(s string) bool {
	for _, r := range s {
		if unicode.Is(unicode.Cc, r) {
			return true
		}
	}
	return false
}

func secretOK(s, what string) (string, error) {
	if s == "" {
		return "", bad("no %s", what)
	}
	if utf8.RuneCountInString(s) > linksMaxSecret {
		return "", bad("the %s is too long", what)
	}
	if hasControl(s) {
		return "", bad("the %s has control characters in it", what)
	}
	return s, nil
}

func pathOK(s string) (string, error) {
	if utf8.RuneCountInString(s) > linksMaxPath {
		return "", bad("the path is too long")
	}
	if hasControl(s) || strings.Contains(s, " ") {
		return "", bad("the path has spaces or control characters in it")
	}
	if !strings.HasPrefix(s, "/") {
		s = "/" + s
	}
	return s, nil
}

func sniOK(text string) (string, error) {
	s := strings.TrimSpace(text)
	if s == "" {
		return "", nil
	}
	h, err := hostOK(s, "SNI")
	if err != nil {
		return "", err
	}
	if isAddress(h) {
		return "", nil
	}
	return h, nil
}

func truthy(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}

// b64 is base64 in any of the shapes links use. nil when it is not base64.
func b64(text string) []byte {
	s := strings.Join(strings.Fields(text), "")
	if s == "" || !reB64.MatchString(s) {
		return nil
	}
	s = strings.TrimRight(s, "=")
	s = strings.NewReplacer("-", "+", "_", "/").Replace(s)
	if pad := len(s) % 4; pad != 0 {
		s += strings.Repeat("=", 4-pad)
	}
	out, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return nil
	}
	return out
}

func unquote(s string, plus bool) string {
	if plus {
		if v, err := url.QueryUnescape(s); err == nil {
			return v
		}
		return strings.ReplaceAll(s, "+", " ")
	}
	if v, err := url.PathUnescape(s); err == nil {
		return v
	}
	return s
}

type query map[string]string

func parseQuery(text string) (query, error) {
	out := query{}
	if text == "" {
		return out, nil
	}
	pairs := strings.Split(text, "&")
	n := 0
	for _, p := range pairs {
		if p == "" {
			continue
		}
		n++
		if n > linksMaxQueryKeys {
			return nil, bad("the link has too many parameters")
		}
		k, v, _ := strings.Cut(p, "=")
		k = strings.TrimSpace(unquote(k, true))
		v = strings.TrimSpace(unquote(v, true))
		if _, seen := out[k]; !seen {
			out[k] = v
		}
	}
	return out, nil
}

func (q query) first(def string, names ...string) string {
	for _, n := range names {
		if v, ok := q[n]; ok && v != "" {
			return v
		}
	}
	return def
}

// splitHostPort is host:port or [v6]:port. A port list (hysteria2's port
// hopping, "443,8000-9000") is taken at its first port.
func splitHostPort(text string) (string, string, error) {
	s := strings.TrimSpace(text)
	m := reHostPort6.FindStringSubmatch(s)
	if m == nil {
		m = reHostPort.FindStringSubmatch(s)
	}
	if m == nil {
		return "", "", bad("no port after the server address")
	}
	p := rePorts.FindStringSubmatch(m[2])
	if p == nil {
		return "", "", bad("the port is not a number")
	}
	return m[1], p[1], nil
}

// -- the shapes links come in ----------------------------------------------------

// raw is what a reader pulled out, before any of it is believed.
type raw map[string]any

func (r raw) s(key string) string {
	switch v := r[key].(type) {
	case string:
		return v
	case float64:
		if v == float64(int64(v)) {
			return strconv.FormatInt(int64(v), 10)
		}
		return strconv.FormatFloat(v, 'f', -1, 64)
	case int:
		return strconv.Itoa(v)
	case bool:
		if v {
			return "true"
		}
		return "false"
	}
	return ""
}

func (r raw) b(key string) bool {
	switch v := r[key].(type) {
	case bool:
		return v
	case string:
		return truthy(v)
	case float64:
		return v != 0
	}
	return false
}

func urlParts(link string) (user, host, port string, q query, name string, err error) {
	body, frag, _ := strings.Cut(link, "#")
	_, rest, _ := strings.Cut(body, "://")
	netloc := rest
	if i := strings.IndexAny(rest, "/?"); i >= 0 {
		netloc = rest[:i]
	}
	qs := ""
	if i := strings.Index(rest, "?"); i >= 0 {
		qs = rest[i+1:]
	}
	at := strings.LastIndex(netloc, "@")
	if at < 0 {
		err = bad("no id or password before the server address")
		return
	}
	host, port, err = splitHostPort(netloc[at+1:])
	if err != nil {
		return
	}
	q, err = parseQuery(qs)
	if err != nil {
		return
	}
	user = unquote(netloc[:at], false)
	name = unquote(frag, false)
	return
}

func common(q query) raw {
	return raw{
		"net":         q.first("tcp", "type", "net"),
		"path":        q.first("", "path"),
		"host_header": q.first("", "host"),
		"service":     q.first("", "serviceName", "service_name"),
		"mode":        q.first("", "mode"),
		"header":      q.first("none", "headerType", "header_type"),
		"extra":       q.first("", "extra"),
		"security":    q.first("none", "security"),
		"sni":         q.first("", "sni", "peer", "servername"),
		"alpn":        q.first("", "alpn"),
		"fp":          q.first("", "fp", "fingerprint"),
		"pbk":         q.first("", "pbk", "publicKey", "public_key"),
		"sid":         q.first("", "sid", "shortId", "short_id"),
		"spx":         q.first("", "spx", "spiderX"),
		"insecure": truthy(q.first("0", "allowInsecure", "allow_insecure",
			"insecure", "skip-cert-verify")),
	}
}

func readVless(link string) (raw, error) {
	user, host, port, q, name, err := urlParts(link)
	if err != nil {
		return nil, err
	}
	r := common(q)
	r["proto"], r["name"], r["host"], r["port"], r["uuid"] = "vless", name, host, port, user
	r["flow"] = q.first("", "flow")
	r["encryption"] = q.first("none", "encryption")
	return r, nil
}

func readTrojan(link string) (raw, error) {
	user, host, port, q, name, err := urlParts(link)
	if err != nil {
		return nil, err
	}
	r := common(q)
	// Trojan without a word about security is TLS: every client assumes it.
	if _, ok := q["security"]; !ok {
		r["security"] = "tls"
	}
	r["proto"], r["name"], r["host"], r["port"], r["password"] = "trojan", name, host, port, user
	return r, nil
}

func readVmess(link string) (raw, error) {
	body, _, _ := strings.Cut(link[len("vmess://"):], "#")
	data := b64(body)
	notJSON := bad("a vmess link is base64 of a JSON object, and this is not")
	if data == nil || len(data) > LinksMaxLink || !utf8.Valid(data) {
		return nil, notJSON
	}
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil || m == nil {
		return nil, notJSON
	}
	mr := raw(m)
	get := func(def string, names ...string) string {
		for _, n := range names {
			if v := mr.s(n); v != "" {
				return v
			}
		}
		return def
	}
	nt := get("tcp", "net")
	tls := get("none", "tls")
	sec := "none"
	if tls == "tls" || tls == "reality" {
		sec = tls
	}
	r := raw{"proto": "vmess", "name": get("", "ps", "remarks"), "host": get("", "add"),
		"port": get("", "port"), "uuid": get("", "id"), "alter_id": get("0", "aid"),
		"cipher": get("auto", "scy", "security"), "net": nt, "path": get("", "path"),
		"host_header": get("", "host"), "service": "", "mode": "", "header": "none",
		"extra": "", "security": sec, "sni": get("", "sni", "peer"),
		"alpn": get("", "alpn"), "fp": get("", "fp"), "pbk": get("", "pbk"),
		"sid": get("", "sid"), "spx": get("", "spx"),
		"insecure": truthy(get("0", "allowInsecure", "insecure", "skip-cert-verify"))}
	// In this format `type` is the header for tcp, the mode for grpc and
	// xhttp, and `path` is the service name for grpc.
	kind := get("", "type")
	n, ok := linkNets[strings.ToLower(nt)]
	if !ok {
		n = strings.ToLower(nt)
	}
	switch n {
	case "tcp":
		if kind == "" {
			kind = "none"
		}
		r["header"] = kind
	case "grpc":
		r["service"], r["path"], r["mode"] = r["path"], "", kind
	case "xhttp":
		r["mode"] = kind
	}
	return r, nil
}

func readSS(link string) (raw, error) {
	body, frag, _ := strings.Cut(link[len("ss://"):], "#")
	name := unquote(frag, false)
	body, qs, _ := strings.Cut(body, "?")
	body = strings.TrimRight(body, "/")
	q, err := parseQuery(qs)
	if err != nil {
		return nil, err
	}
	if q["plugin"] != "" {
		return nil, bad("Shadowsocks plugins (obfs, v2ray-plugin) are not supported yet")
	}
	unreadable := bad("the method and password are not readable")
	var userinfo, hostport string
	if at := strings.LastIndex(body, "@"); at >= 0 {
		userinfo, hostport = unquote(body[:at], false), body[at+1:]
		if !strings.Contains(userinfo, ":") {
			d := b64(userinfo)
			if d == nil {
				return nil, unreadable
			}
			userinfo = strings.ToValidUTF8(string(d), "�")
		}
	} else {
		d := b64(body)
		if d == nil {
			return nil, unreadable
		}
		text := strings.ToValidUTF8(string(d), "�")
		at := strings.LastIndex(text, "@")
		if at < 0 {
			return nil, bad("no server address in the link")
		}
		userinfo, hostport = text[:at], text[at+1:]
	}
	method, password, ok := strings.Cut(userinfo, ":")
	if !ok {
		return nil, unreadable
	}
	host, port, err := splitHostPort(hostport)
	if err != nil {
		return nil, err
	}
	return raw{"proto": "ss", "name": name, "host": host, "port": port,
		"method": method, "password": password}, nil
}

func readHysteria2(link string) (raw, error) {
	_, rest, _ := strings.Cut(link, "://")
	user, host, port, q, name, err := urlParts("hysteria2://" + rest)
	if err != nil {
		return nil, err
	}
	return raw{"proto": "hysteria2", "name": name, "host": host, "port": port,
		"password": user, "security": "tls", "sni": q.first("", "sni", "peer"),
		"alpn":          q.first("", "alpn"),
		"insecure":      truthy(q.first("0", "insecure", "allowInsecure")),
		"obfs":          q.first("", "obfs"),
		"obfs_password": q.first("", "obfs-password", "obfs_password")}, nil
}

func readTUIC(link string) (raw, error) {
	user, host, port, q, name, err := urlParts(link)
	if err != nil {
		return nil, err
	}
	uid, password, _ := strings.Cut(user, ":")
	return raw{"proto": "tuic", "name": name, "host": host, "port": port,
		"uuid": uid, "password": password, "security": "tls",
		"sni": q.first("", "sni"), "alpn": q.first("h3", "alpn"),
		"insecure":   truthy(q.first("0", "allow_insecure", "allowInsecure", "insecure")),
		"congestion": q.first("bbr", "congestion_control", "congestion"),
		"udp_relay":  q.first("native", "udp_relay_mode")}, nil
}

var linkReaders = map[string]func(string) (raw, error){"vless": readVless,
	"vmess": readVmess, "trojan": readTrojan, "ss": readSS,
	"hysteria2": readHysteria2, "tuic": readTUIC}

// -- one shape out of all of them -------------------------------------------------

// finish is the checked Config, from what a reader pulled out - or from a
// Config read back from storage, which is checked again rather than
// believed. Every field is checked here and only here.
func finish(r raw, link string) (*Config, error) {
	proto := r.s("proto")
	c := &Config{Proto: proto}
	var err error
	if c.Host, err = hostOK(r.s("host"), "server"); err != nil {
		return nil, err
	}
	if c.Port, err = portOK(r.s("port")); err != nil {
		return nil, err
	}
	c.Name = CleanName(r.s("name"))

	switch proto {
	case "vless", "vmess":
		if c.UUID, err = uuidOK(r.s("uuid")); err != nil {
			return nil, err
		}
	case "tuic":
		if c.UUID, err = uuidOK(r.s("uuid")); err != nil {
			return nil, err
		}
		if c.Password, err = secretOK(r.s("password"), "password"); err != nil {
			return nil, err
		}
	case "trojan", "hysteria2":
		if c.Password, err = secretOK(r.s("password"), "password"); err != nil {
			return nil, err
		}
	case "ss":
		m := strings.ToLower(strings.TrimSpace(r.s("method")))
		if a, ok := linkSSAliases[m]; ok {
			m = a
		}
		if !linkSSMethods[m] {
			return nil, bad("Shadowsocks method %q is not one that is supported", trunc(m, 40))
		}
		c.Method = m
		if c.Password, err = secretOK(r.s("password"), "password"); err != nil {
			return nil, err
		}
		c.Security, c.Net = "none", "tcp"
	default:
		return nil, bad("%q is not a protocol this reads", trunc(proto, 12))
	}

	if proto == "vmess" {
		aid, aerr := strconv.Atoi(defaultTo(r.s("alter_id"), "0"))
		if aerr != nil {
			return nil, bad("alterId is not a number")
		}
		if aid < 0 || aid > 65535 {
			return nil, bad("alterId is out of range")
		}
		c.AlterID = aid
		c.Cipher = strings.ToLower(defaultTo(r.s("cipher"), "auto"))
		if !linkVmessCiphers[c.Cipher] {
			c.Cipher = "auto"
		}
	}
	if proto == "vless" {
		enc := strings.TrimSpace(defaultTo(r.s("encryption"), "none"))
		if enc != "none" && (len(enc) > 1024 || !reEncryption.MatchString(enc)) {
			return nil, bad("the VLESS encryption setting is not readable")
		}
		c.Encryption = enc
	}
	if proto == "vless" || proto == "vmess" || proto == "trojan" {
		if err := transportOf(c, r); err != nil {
			return nil, err
		}
		if err := tlsOf(c, r); err != nil {
			return nil, err
		}
		flow := ""
		if proto == "vless" {
			flow = strings.ToLower(strings.TrimSpace(r.s("flow")))
		}
		if flow != "" && flow != "xtls-rprx-vision" {
			return nil, bad("flow %q is not supported", trunc(flow, 40))
		}
		// Vision is TLS or Reality over plain TCP and nothing else; a link
		// that asks for it over anything else was made wrong.
		if flow != "" && (c.Net != "tcp" || c.Security == "none") {
			flow = ""
		}
		c.Flow = flow
	}
	if proto == "hysteria2" || proto == "tuic" {
		c.Net, c.Security = "quic", "tls"
		if c.SNI, err = sniOK(r.s("sni")); err != nil {
			return nil, err
		}
		c.ALPN = alpnOf(r["alpn"])
		c.Insecure = r.b("insecure")
	}
	if proto == "hysteria2" {
		obfs := strings.ToLower(strings.TrimSpace(r.s("obfs")))
		if obfs != "" && obfs != "salamander" {
			return nil, bad("Hysteria2 obfuscation %q is not supported", trunc(obfs, 40))
		}
		c.Obfs = obfs
		if obfs != "" {
			if c.ObfsPassword, err = secretOK(r.s("obfs_password"), "obfuscation password"); err != nil {
				return nil, err
			}
		}
	}
	if proto == "tuic" {
		c.Congestion = strings.ToLower(defaultTo(r.s("congestion"), "bbr"))
		if c.Congestion != "bbr" && c.Congestion != "cubic" && c.Congestion != "new_reno" {
			c.Congestion = "bbr"
		}
		c.UDPRelay = strings.ToLower(defaultTo(r.s("udp_relay"), "native"))
		if c.UDPRelay != "native" && c.UDPRelay != "quic" {
			c.UDPRelay = "native"
		}
		if len(c.ALPN) == 0 {
			c.ALPN = []string{"h3"}
		}
	}
	if c.ALPN == nil {
		c.ALPN = []string{}
	}
	if c.Name == "" {
		c.Name = proto + " " + c.Host
	}
	c.Link = link
	c.AllowInsecure = r.b("allow_insecure")
	c.ID = c.id()
	return c, nil
}

func defaultTo(s, d string) string {
	if s == "" {
		return d
	}
	return s
}

func trunc(s string, n int) string {
	if utf8.RuneCountInString(s) > n {
		return string([]rune(s)[:n])
	}
	return s
}

func alpnOf(v any) []string {
	var items []string
	switch t := v.(type) {
	case []any:
		for _, x := range t {
			if s, ok := x.(string); ok {
				items = append(items, s)
			}
		}
	case []string:
		items = t
	case string:
		items = strings.Split(t, ",")
	}
	out := []string{}
	for _, it := range items {
		a := strings.ToLower(strings.TrimSpace(it))
		for _, ok := range linkALPNs {
			if a == ok && !contains(out, a) {
				out = append(out, a)
			}
		}
	}
	return out
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func transportOf(c *Config, r raw) error {
	rawNet := strings.ToLower(strings.TrimSpace(defaultTo(r.s("net"), "tcp")))
	if what, ok := linkNetsRefused[rawNet]; ok {
		return bad("%s is not supported", what)
	}
	n, ok := linkNets[rawNet]
	if !ok {
		return bad("transport %q is not supported", trunc(rawNet, 40))
	}
	c.Net, c.Header = n, "none"
	if hh := strings.TrimSpace(r.s("host_header")); hh != "" {
		first, _, _ := strings.Cut(hh, ",")
		h, err := hostOK(strings.TrimSpace(first), "Host header")
		if err != nil {
			return err
		}
		c.HostHeader = h
	}
	switch n {
	case "ws", "httpupgrade", "http", "xhttp":
		p, err := pathOK(defaultTo(r.s("path"), "/"))
		if err != nil {
			return err
		}
		if n == "ws" {
			// "?ed=2048" on a WebSocket path is Xray's way of saying early
			// data; sing-box takes it as two settings instead.
			base, qs, _ := strings.Cut(p, "?")
			if q, _ := parseQuery(qs); q != nil && q["ed"] != "" {
				if ed, err := strconv.Atoi(q["ed"]); err == nil {
					c.ED = max(0, min(ed, 65535))
				}
				p = defaultTo(base, "/")
			}
		}
		c.Path = p
	case "grpc":
		svc := strings.Trim(strings.TrimSpace(defaultTo(r.s("service"), r.s("path"))), "/")
		if !reService.MatchString(svc) {
			return bad("the gRPC service name is not readable")
		}
		c.Service = svc
		c.Mode = "gun"
		if strings.ToLower(strings.TrimSpace(r.s("mode"))) == "multi" {
			c.Mode = "multi"
		}
	case "tcp":
		h := strings.ToLower(strings.TrimSpace(defaultTo(r.s("header"), "none")))
		if h != "none" && h != "http" {
			return bad("TCP header %q is not supported", trunc(h, 40))
		}
		c.Header = h
		if h == "http" {
			p, err := pathOK(defaultTo(r.s("path"), "/"))
			if err != nil {
				return err
			}
			c.Path = p
		}
	}
	if n == "xhttp" {
		m := strings.ToLower(strings.TrimSpace(defaultTo(r.s("mode"), "auto")))
		if !linkXHTTPModes[m] {
			m = "auto"
		}
		c.Mode = m
		switch e := r["extra"].(type) {
		case map[string]any:
			c.Extra = e
		case string:
			if e != "" {
				if len(e) > linksMaxExtra {
					return bad("the xhttp extra settings are too long")
				}
				var m map[string]any
				if err := json.Unmarshal([]byte(e), &m); err != nil || m == nil {
					return bad("the xhttp extra settings are not a JSON object")
				}
				c.Extra = m
			}
		}
	}
	return nil
}

func tlsOf(c *Config, r raw) error {
	sec := strings.ToLower(strings.TrimSpace(r.s("security")))
	if sec == "" {
		sec = "none"
	}
	if sec != "none" && sec != "tls" && sec != "reality" {
		return bad("security %q is not supported", trunc(sec, 40))
	}
	c.Security = sec
	c.ALPN = []string{}
	if sec == "none" {
		return nil
	}
	var err error
	if c.SNI, err = sniOK(r.s("sni")); err != nil {
		return err
	}
	c.ALPN = alpnOf(r["alpn"])
	fp := strings.ToLower(strings.TrimSpace(r.s("fp")))
	if fp == "randomizednoalpn" {
		fp = "randomized"
	}
	if fp != "" && !linkFingerprints[fp] {
		fp = "chrome"
	}
	c.FP = fp
	if sec == "tls" {
		c.Insecure = r.b("insecure")
	}
	if sec == "reality" {
		pbk := strings.TrimRight(strings.TrimSpace(r.s("pbk")), "=")
		if !rePBK.MatchString(pbk) {
			return bad("the Reality public key is missing or not readable")
		}
		sid := strings.TrimSpace(r.s("sid"))
		if !reSID.MatchString(sid) {
			return bad("the Reality short id is not readable")
		}
		c.PBK, c.SID = pbk, strings.ToLower(sid)
		if spx := r.s("spx"); spx != "" {
			if c.SPX, err = pathOK(spx); err != nil {
				return err
			}
		}
		// Reality is a browser's handshake or nothing; both cores refuse it
		// without a fingerprint.
		if c.FP == "" {
			c.FP = "chrome"
		}
		if c.SNI == "" {
			return bad("a Reality link needs the name it borrows (sni)")
		}
	}
	return nil
}

// -- what the rest of the app asks -----------------------------------------------

func (c *Config) id() string {
	cp := *c
	cp.ID, cp.Name, cp.Link, cp.AllowInsecure = "", "", "", false
	b, _ := json.Marshal(cp)
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])[:16]
}

// Engine is "xray" for what only Xray carries, otherwise "sing-box". The
// phone has no Xray yet, so those are refused when connecting.
func (c *Config) Engine() string {
	switch {
	case c.Net == "xhttp",
		c.Net == "tcp" && c.Header == "http",
		c.Net == "grpc" && c.Mode == "multi",
		c.Proto == "vless" && c.Encryption != "" && c.Encryption != "none":
		return "xray"
	}
	return "sing-box"
}

// Warnings is what to tell the person about it.
func (c *Config) Warnings() []string {
	out := []string{}
	switch {
	case (c.Proto == "vless" || c.Proto == "trojan") && c.Security == "none":
		if c.Proto == "vless" && c.Encryption != "" && c.Encryption != "none" {
			out = append(out, WarnNoTLS)
		} else {
			out = append(out, WarnPlain)
		}
	case c.Proto == "vmess" && c.Security == "none":
		if c.Cipher == "none" || c.Cipher == "zero" {
			out = append(out, WarnPlain)
		} else {
			out = append(out, WarnNoTLS)
		}
	case c.Proto == "ss":
		out = append(out, WarnNoTLS)
	}
	if c.Insecure {
		out = append(out, WarnInsecure)
	}
	return out
}

// Label is the short name of what it is: "VLESS·Reality·Vision".
func (c *Config) Label() string {
	names := map[string]string{"vless": "VLESS", "vmess": "VMess", "trojan": "Trojan",
		"ss": "SS", "hysteria2": "Hysteria2", "tuic": "TUIC"}
	nets := map[string]string{"ws": "WS", "grpc": "gRPC", "httpupgrade": "HTTPUpgrade",
		"http": "H2", "xhttp": "xhttp"}
	parts := []string{defaultTo(names[c.Proto], "?")}
	if n, ok := nets[c.Net]; ok {
		parts = append(parts, n)
	}
	if c.Security == "reality" {
		parts = append(parts, "Reality")
	} else if c.Security == "tls" && c.Proto != "hysteria2" && c.Proto != "tuic" {
		parts = append(parts, "TLS")
	}
	if c.Flow != "" {
		parts = append(parts, "Vision")
	}
	return strings.Join(parts, "·")
}

// HasTLS is whether there is a certificate to check at all.
func (c *Config) HasTLS() bool {
	return c.Security == "tls" || c.Proto == "hysteria2" || c.Proto == "tuic"
}

// maskedLink is what may be said about a link in a refusal: its scheme.
func maskedLink(text string) string {
	if !strings.Contains(text, "://") {
		return "…"
	}
	scheme, _, _ := strings.Cut(text, "://")
	scheme = strings.ToLower(trunc(scheme, 12))
	if !reScheme.MatchString(scheme) {
		return "…"
	}
	return scheme + "://…"
}

// ParseLink reads one link.
func ParseLink(link string) (*Config, error) {
	link = strings.TrimSpace(link)
	if len(link) > LinksMaxLink {
		return nil, bad("the link is too long")
	}
	scheme := ""
	if s, _, ok := strings.Cut(link, "://"); ok {
		scheme = strings.ToLower(s)
	}
	proto, ok := linkSchemes[scheme]
	if !ok {
		if scheme == "" {
			return nil, bad("not a link")
		}
		return nil, bad("%q links are not supported", trunc(scheme, 12)+"://")
	}
	if hasControl(link) {
		return nil, bad("the link has control characters in it")
	}
	r, err := linkReaders[proto](link)
	if err != nil {
		return nil, err
	}
	return finish(r, link)
}

// ParseConfig checks a Config read back from storage, the same way a link
// is checked when it first arrives.
func ParseConfig(data []byte) (*Config, error) {
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil || m == nil {
		return nil, bad("not a stored config")
	}
	link, _ := m["link"].(string)
	return finish(raw(m), link)
}

// ParseLinks is every link in a paste or a subscription body: what was
// taken, and what was not and why. Duplicates (by id) are dropped quietly;
// more than limit is cut there and said.
func ParseLinks(text string, limit int) ([]*Config, []LinkProblem, error) {
	if len(text) > LinksMaxText {
		return nil, nil, bad("that is more than this app reads at once (512 KB)")
	}
	text = strings.TrimSpace(strings.TrimPrefix(text, "\ufeff"))
	if text == "" {
		return nil, nil, bad("that is empty")
	}
	if strings.HasPrefix(text, "{") {
		var data struct {
			Outbounds []map[string]any `json:"outbounds"`
		}
		if json.Unmarshal([]byte(text), &data) == nil && data.Outbounds != nil {
			c, p := fromSingbox(data.Outbounds, limit)
			return c, p, nil
		}
	}
	tokens := strings.Fields(text)
	hasLink := false
	for _, t := range tokens {
		if strings.Contains(t, "://") {
			hasLink = true
			break
		}
	}
	if !hasLink {
		if d := b64(text); d != nil && utf8.Valid(d) && strings.Contains(string(d), "://") {
			tokens = strings.Fields(string(d))
		}
	}
	var configs []*Config
	var problems []LinkProblem
	seen := map[string]bool{}
	for i, t := range tokens {
		if len(configs) >= limit {
			problems = append(problems, LinkProblem{N: i + 1,
				Why: fmt.Sprintf("only the first %d are kept", limit)})
			break
		}
		c, err := ParseLink(t)
		if err != nil {
			problems = append(problems, LinkProblem{N: i + 1, What: maskedLink(t), Why: err.Error()})
			continue
		}
		if seen[c.ID] {
			continue
		}
		seen[c.ID] = true
		configs = append(configs, c)
	}
	return configs, problems, nil
}

var singboxTypes = map[string]string{"vless": "vless", "vmess": "vmess",
	"trojan": "trojan", "shadowsocks": "ss", "hysteria2": "hysteria2", "tuic": "tuic"}

// fromSingbox reads sing-box's own JSON, as some subscriptions serve it.
func fromSingbox(outbounds []map[string]any, limit int) ([]*Config, []LinkProblem) {
	var configs []*Config
	var problems []LinkProblem
	seen := map[string]bool{}
	n := 0
	for _, o := range outbounds {
		typ, _ := o["type"].(string)
		proto, ok := singboxTypes[typ]
		if !ok {
			continue
		}
		n++
		if n > 5000 {
			break
		}
		if len(configs) >= limit {
			problems = append(problems, LinkProblem{N: n,
				Why: fmt.Sprintf("only the first %d are kept", limit)})
			break
		}
		obj := func(m map[string]any, key string) map[string]any {
			v, _ := m[key].(map[string]any)
			if v == nil {
				return map[string]any{}
			}
			return v
		}
		str := func(m map[string]any, key string) string { return raw(m).s(key) }
		tls, tr := obj(o, "tls"), obj(o, "transport")
		reality, utls := obj(tls, "reality"), obj(tls, "utls")
		headers, obfs := obj(tr, "headers"), obj(o, "obfs")
		hostHeader := str(headers, "Host")
		if hostHeader == "" {
			if hs, ok := tr["host"].([]any); ok && len(hs) > 0 {
				hostHeader, _ = hs[0].(string)
			} else {
				hostHeader = str(tr, "host")
			}
		}
		security := "none"
		if raw(tls).b("enabled") {
			security = "tls"
			if raw(reality).b("enabled") {
				security = "reality"
			}
		}
		if proto == "hysteria2" || proto == "tuic" {
			security = "tls"
		}
		r := raw{"proto": proto, "name": str(o, "tag"), "host": str(o, "server"),
			"port": str(o, "server_port"), "uuid": str(o, "uuid"),
			"password": str(o, "password"), "method": str(o, "method"),
			"alter_id": defaultTo(str(o, "alter_id"), "0"),
			"cipher":   defaultTo(str(o, "security"), "auto"), "flow": str(o, "flow"),
			"net": defaultTo(str(tr, "type"), "tcp"), "path": str(tr, "path"),
			"host_header": hostHeader, "service": str(tr, "service_name"),
			"mode": "", "header": "none", "extra": "", "security": security,
			"sni": str(tls, "server_name"), "alpn": tls["alpn"],
			"fp": str(utls, "fingerprint"), "pbk": str(reality, "public_key"),
			"sid": str(reality, "short_id"), "spx": "",
			"insecure": raw(tls).b("insecure"), "obfs": str(obfs, "type"),
			"obfs_password": str(obfs, "password"),
			"congestion":    defaultTo(str(o, "congestion_control"), "bbr"),
			"udp_relay":     defaultTo(str(o, "udp_relay_mode"), "native")}
		c, err := finish(r, "")
		if err != nil {
			problems = append(problems, LinkProblem{N: n, What: typ + "://…", Why: err.Error()})
			continue
		}
		if seen[c.ID] {
			continue
		}
		seen[c.ID] = true
		configs = append(configs, c)
	}
	return configs, problems
}

// LinksErr says whether an error is a link being refused (to be shown as it
// is) rather than something going wrong.
func LinksErr(err error) bool {
	var e *ErrLink
	return errors.As(err, &e)
}
