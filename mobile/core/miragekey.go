package core

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"strconv"
	"strings"
)

// The key the server's installer prints: mirage:// and then base64url of a
// JSON object holding the server, its certificate's pin when it is reached
// by address, both passwords, the TLS port when it is not 443 and - when the
// server offers it - Shadowsocks.
// Pasted into the app, it is the whole of setting a server up.
//
// The desktop reads the same key in app/tunnel.py (parse_key); the two
// check the same things and refuse the same keys.

// KeyPrefix starts every key.
const KeyPrefix = "mirage://"

// OldKeyPrefix is how keys began before the app was called Mirage. Servers
// installed then still print it, so it is read forever; the payload after
// either prefix is the same.
const OldKeyPrefix = "relay://"

// MirageKey is a key, read and checked.
type MirageKey struct {
	Host        string `json:"host"`
	Pin         string `json:"pin"`
	Password    string `json:"password"`
	APIPassword string `json:"apiPassword"`
	// Port is where the server listens for TLS: 443 unless the installer was
	// given another with --port, in which case the key says so.
	Port int          `json:"port"`
	SS   *MirageKeySS `json:"ss,omitempty"`
	// The locations the server keeps for other devices, when it has them.
	// See export.go.
	Export *MirageKeyExport `json:"export,omitempty"`
}

// MirageKeySS is the server's Shadowsocks: one key, two ports - Port exits at
// the server, ExitPort at the provider location the app has chosen.
type MirageKeySS struct {
	Method   string `json:"method"`
	Password string `json:"password"`
	Port     int    `json:"port"`
	ExitPort int    `json:"exitPort"`
}

// SSMethods are the AEAD methods the server and both apps agree on.
var SSMethods = []string{"chacha20-ietf-poly1305", "aes-256-gcm", "aes-128-gcm"}

// ParseMirageKey reads a key and checks every field, because it arrives
// pasted out of a chat message and a value that is not what it claims would
// otherwise turn up much later, as a connection that fails for no reason the
// window can name.
func ParseMirageKey(text string) (*MirageKey, error) {
	raw := strings.TrimSpace(text)
	for _, p := range []string{KeyPrefix, OldKeyPrefix} {
		if strings.HasPrefix(strings.ToLower(raw), p) {
			raw = raw[len(p):]
			break
		}
	}
	// Chat apps wrap long lines; a key is one token, so whitespace is noise.
	raw = strings.Join(strings.Fields(raw), "")
	if raw == "" {
		return nil, errors.New("that is empty")
	}
	body, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(raw, "="))
	if err != nil {
		return nil, errors.New("that is not a Mirage key - it should start with mirage://")
	}
	var wire struct {
		V    int    `json:"v"`
		Host string `json:"host"`
		Pin  string `json:"pin"`
		// Raw, because "absent" and "present but not a whole number" have to
		// stay apart: a plain int would read "8443.5" and "true" as errors
		// with the wrong words and a string as zero.
		Port json.RawMessage `json:"port"`
		PW   string          `json:"pw"`
		APW  string          `json:"apw"`
		SS   *struct {
			Method   string `json:"method"`
			Password string `json:"password"`
			Port     int    `json:"port"`
			ExitPort int    `json:"exitPort"`
		} `json:"ss"`
		X *struct {
			ID string `json:"id"`
			N  int    `json:"n"`
		} `json:"x"`
	}
	if err := json.Unmarshal(body, &wire); err != nil {
		return nil, errors.New("that is not a Mirage key - it should start with mirage://")
	}
	if wire.V != 1 {
		return nil, errors.New("that key is from a newer Mirage than this one")
	}
	k := &MirageKey{
		Host:        strings.TrimSpace(wire.Host),
		Pin:         strings.ToLower(strings.TrimSpace(wire.Pin)),
		Password:    strings.TrimSpace(wire.PW),
		APIPassword: strings.TrimSpace(wire.APW),
	}
	k.Port = 443
	if raw := strings.TrimSpace(string(wire.Port)); raw != "" && raw != "null" {
		// Digits only: no sign, fraction, exponent, quotes or true/false.
		n, err := strconv.Atoi(raw)
		if err != nil || strings.Trim(raw, "0123456789") != "" || !validPort(n) {
			return nil, errors.New("the port in that key is malformed")
		}
		k.Port = n
	}
	switch {
	case k.Host == "":
		return nil, errors.New("the key has no host")
	case k.Password == "":
		return nil, errors.New("the key has no pw")
	case k.APIPassword == "":
		return nil, errors.New("the key has no apw")
	case !validHost(k.Host):
		return nil, errors.New("the server in that key is not an address or a name")
	case k.Pin != "" && !isHex64(k.Pin):
		return nil, errors.New("the certificate pin in that key is malformed")
	case !validSecret(k.Password):
		return nil, errors.New("the tunnel password in that key " + secretRule)
	case !validSecret(k.APIPassword):
		return nil, errors.New("the API password in that key " + secretRule)
	}
	if wire.SS != nil {
		ss := wire.SS
		if ss.Password != "" && !validSecret(ss.Password) {
			return nil, errors.New("the Shadowsocks password in that key " + secretRule)
		}
		ok := ss.Password != "" && validPort(ss.Port) && validPort(ss.ExitPort)
		known := false
		for _, m := range SSMethods {
			known = known || ss.Method == m
		}
		if !ok || !known {
			return nil, errors.New("the key's Shadowsocks settings are malformed")
		}
		k.SS = &MirageKeySS{Method: ss.Method, Password: ss.Password,
			Port: ss.Port, ExitPort: ss.ExitPort}
	}
	if wire.X != nil {
		if !validExportID(wire.X.ID) || wire.X.N < 1 || wire.X.N > MaxExportSlots {
			return nil, errors.New("the key's exported locations are malformed")
		}
		k.Export = &MirageKeyExport{ID: wire.X.ID, Slots: wire.X.N}
	}
	return k, nil
}

func validPort(p int) bool { return p > 0 && p < 65536 }

// validHost is the rule every app holds a key's host to: an IPv4 literal, or
// a hostname of LDH labels - letters, digits and hyphens, one to 63 of them,
// not starting or ending with a hyphen, 253 in all. The host ends up in URLs,
// a Host header, a TLS name and the desktop's config files, and anything
// else is a character one of those reads as syntax.
func validHost(h string) bool {
	if ip := net.ParseIP(h); ip != nil {
		return ip.To4() != nil && !strings.Contains(h, ":")
	}
	if h == "" || len(h) > 253 {
		return false
	}
	for _, part := range strings.Split(h, ".") {
		if part == "" || len(part) > 63 || part[0] == '-' || part[len(part)-1] == '-' {
			return false
		}
		for _, r := range part {
			if !(r == '-' || r >= '0' && r <= '9' || r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z') {
				return false
			}
		}
	}
	return true
}

// secretRule is validSecret, said to a person.
const secretRule = "must be 1 to 128 printable characters, without spaces or any of ' \" ` $ \\ { } #"

// validSecret is the rule every app holds a key's passwords to - the tunnel's,
// the API's and Shadowsocks': 1 to 128 characters of printable ASCII
// (0x21-0x7E), none of them ' " ` $ \ { } #. Those are the characters a
// shell, a YAML or JSON writer or a config comment would read as syntax on
// the way into the desktop's files and the server's, so a key carrying one
// is refused here rather than turning into a config that means something
// else. The installer's own passwords are 20 letters and digits and always
// pass.
func validSecret(s string) bool {
	if len(s) < 1 || len(s) > 128 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c < 0x21 || c > 0x7e {
			return false
		}
		switch c {
		case '\'', '"', '`', '$', '\\', '{', '}', '#':
			return false
		}
	}
	return true
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, r := range s {
		if !(r >= '0' && r <= '9' || r >= 'a' && r <= 'f') {
			return false
		}
	}
	return true
}
