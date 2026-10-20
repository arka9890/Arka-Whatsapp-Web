//go:build windows

package main

import "testing"

func TestIsInstallerAsset(t *testing.T) {
	for _, tc := range []struct {
		name string
		want bool
	}{
		{"WhatsAppWebClient-Setup-1.0.0.exe", true},
		{"whatsappwebclient-setup-2.1.5.exe", true},
		{"WhatsAppWebClient.exe", false}, // aplikasi telanjang, bukan installer
		{"WhatsAppWebClient-Setup-1.0.0.zip", false},
		{"readme.txt", false},
	} {
		if got := isInstallerAsset(tc.name); got != tc.want {
			t.Errorf("isInstallerAsset(%q) = %v, want %v", tc.name, got, tc.want)
		}
	}
}
