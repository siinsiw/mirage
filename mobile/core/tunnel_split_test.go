//go:build android || linux || darwin

package core

import (
	"bytes"
	"errors"
	"testing"
	"time"
)

// askEitherWay picks the line's answer only when it is Iranian, and otherwise
// the exit's - whichever arrives first, and without waiting the line's whole
// timeout once the exit has answered.
func TestAskEitherWayPicks(t *testing.T) {
	iranian := answerWith([4]byte{2, 144, 1, 1}, [4]byte{2, 144, 1, 2})
	abroad := exampleAnswer()
	sinkhole := answerWith([4]byte{10, 10, 34, 36}, [4]byte{10, 10, 34, 36})
	fromExit := answerWith([4]byte{93, 184, 216, 50}, [4]byte{93, 184, 216, 51})

	answer := func(b []byte, after time.Duration) func([]byte) ([]byte, error) {
		return func([]byte) ([]byte, error) { time.Sleep(after); return b, nil }
	}
	failing := func(after time.Duration) func([]byte) ([]byte, error) {
		return func([]byte) ([]byte, error) { time.Sleep(after); return nil, errors.New("no answer") }
	}

	for _, c := range []struct {
		what       string
		line, exit func([]byte) ([]byte, error)
		want       []byte
		wantErr    bool
	}{
		{"Iranian line answer, line first", answer(iranian, 0), answer(fromExit, 50*time.Millisecond), iranian, false},
		{"Iranian line answer, exit first", answer(iranian, 50*time.Millisecond), answer(fromExit, 0), iranian, false},
		{"line answer abroad", answer(abroad, 0), answer(fromExit, 20*time.Millisecond), fromExit, false},
		{"line forges", answer(sinkhole, 0), answer(fromExit, 20*time.Millisecond), fromExit, false},
		{"line fails", failing(0), answer(fromExit, 20*time.Millisecond), fromExit, false},
		{"exit fails, line Iranian", answer(iranian, 20*time.Millisecond), failing(0), iranian, false},
		{"both fail", failing(0), failing(0), nil, true},
	} {
		got, err := askEitherWay([]byte("q"), c.line, c.exit)
		if (err != nil) != c.wantErr || !bytes.Equal(got, c.want) {
			t.Errorf("%s: got %v, %v", c.what, got, err)
		}
	}

	// A line resolver that never answers holds a name abroad up by the grace,
	// not by its own timeout.
	start := time.Now()
	got, err := askEitherWay([]byte("q"), answer(iranian, 5*time.Second), answer(fromExit, 0))
	if err != nil || !bytes.Equal(got, fromExit) {
		t.Errorf("slow line: got %v, %v", got, err)
	}
	if waited := time.Since(start); waited > smartGrace+time.Second {
		t.Errorf("waited %v for a slow line, more than the grace", waited)
	}
}
