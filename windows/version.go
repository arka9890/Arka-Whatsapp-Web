package main

import (
	"strconv"
	"strings"
)

// version di-isi saat build: go build -ldflags "-X main.version=1.2.3".
var version = "1.0.0"

// isNewer melaporkan apakah versi rilis lebih baru daripada versi yang terpasang.
// Bandingkan per bagian angka ("v1.10.0" sama dengan "1.10.0"); sisi suffix
// non-angka (mis. "-beta") tidak diurutkan, jadi prerelease tidak dianggap baru.
func isNewer(remote, local string) bool {
	a, b := parseVersion(remote), parseVersion(local)
	for i := 0; i < len(a) || i < len(b); i++ {
		x, y := 0, 0
		if i < len(a) {
			x = a[i]
		}
		if i < len(b) {
			y = b[i]
		}
		if x != y {
			return x > y
		}
	}
	return false
}

func parseVersion(v string) []int {
	v = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(v), "v"))
	var out []int
	for _, part := range strings.FieldsFunc(v, func(r rune) bool {
		return r == '.' || r == '-' || r == '+' || r == ' '
	}) {
		n, err := strconv.Atoi(part)
		if err != nil {
			break
		}
		out = append(out, n)
	}
	return out
}
