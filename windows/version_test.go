package main

import "testing"

func TestIsNewer(t *testing.T) {
	cases := []struct {
		remote, local string
		want          bool
	}{
		{"v1.1.0", "1.0.0", true},
		{"1.0.1", "1.0.0", true},
		{"1.10.0", "1.9.0", true}, // angka, bukan string
		{"v2.0.0-beta", "1.9.9", true},
		{"1.0.0", "1.1.0", false},
		{"v1.0.0", "1.0.0", false}, // sama, bukan update
		{"v1.0.0", "1.0.1", false}, // mundur, jangan ditawarkan
		{"", "1.0.0", false},       // rilis tanpa tag tidak dihitung baru
		{"1.0", "1.0.0", false},
		{"1.0.1", "1.0", true},
	}
	for _, c := range cases {
		if got := isNewer(c.remote, c.local); got != c.want {
			t.Errorf("isNewer(%q, %q) = %v, want %v", c.remote, c.local, got, c.want)
		}
	}
}
