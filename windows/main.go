//go:build windows

// WhatsApp Web Client untuk Windows: satu jendela WebView2 yang menampilkan
// web.whatsapp.com, sesi login tersimpan antar-jalankan, dan update otomatis
// dengan mengunduh installer dari GitHub Releases.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"github.com/jchv/go-webview2"
)

const (
	appName    = "WhatsAppWebClient"
	homeURL    = "https://web.whatsapp.com"
	updateRepo = "Dien45/WhatsApp-web" // sumber installer; ganti bila repo pindah
)

const (
	mbYesNo           = 0x04
	mbIconWarning     = 0x30
	mbIconInformation = 0x40
	idYes             = 6
)

var (
	user32          = syscall.NewLazyDLL("user32.dll")
	procMessageBoxW = user32.NewProc("MessageBoxW")
)

func main() {
	dataPath, err := profileDir()
	if err == nil {
		err = os.MkdirAll(dataPath, 0o700)
	}
	if err != nil {
		messageBox("Folder profil tidak dapat dibuat:\n"+err.Error(), appName, mbIconWarning)
		return
	}

	w := webview2.NewWithOptions(webview2.WebViewOptions{
		DataPath:  dataPath,
		AutoFocus: true,
		WindowOptions: webview2.WindowOptions{
			Title:  "WhatsApp Web Client",
			Width:  1280,
			Height: 800,
			Center: true,
		},
	})
	if w == nil {
		// WebView2 Runtime bawaan Windows 11 dan Edge; Windows 10 perlu dipasang manual.
		messageBox("Microsoft Edge WebView2 Runtime tidak ditemukan.\n\n"+
			"Unduh dan pasang dari situs resmi Microsoft:\n"+
			"https://developer.microsoft.com/microsoft-edge/webview2/", appName, mbIconWarning)
		return
	}
	defer w.Destroy()

	w.Navigate(homeURL)
	go checkUpdate(w)
	w.Run()
}

// profileDir: sesi WhatsApp disimpan di luar folder instalasi supaya uninstall /
// update tidak menghapus login.
func profileDir() (string, error) {
	base, err := os.UserCacheDir() // %LocalAppData% di Windows
	if err != nil {
		return "", err
	}
	return filepath.Join(base, appName, "profile"), nil
}

// checkUpdate menawarkan installer bila GitHub punya versi lebih baru.
// Gagal cek jaringan, tidak ada rilis, atau pengguna menolak: aplikasi tetap jalan.
func checkUpdate(w webview2.WebView) {
	setup, tag, err := latestInstaller()
	if err != nil || setup == "" || !isNewer(tag, version) {
		return
	}
	prompt := fmt.Sprintf(
		"Versi baru tersedia: %s (terpasang: %s).\n\n"+
			"Instalasi akan dijalankan dan aplikasi ditutup. Lanjutkan?", tag, version)
	if messageBox(prompt, "Perbarui WhatsApp Web Client", mbYesNo|mbIconInformation) != idYes {
		return
	}
	file, err := download(setup, tag)
	if err != nil {
		messageBox("Unduhan pembaruan gagal:\n"+err.Error(), "Perbarui WhatsApp Web Client", mbIconWarning)
		return
	}
	if err := exec.Command(file).Start(); err != nil {
		messageBox("Installer tidak dapat dijalankan:\n"+err.Error(), "Perbarui WhatsApp Web Client", mbIconWarning)
		return
	}
	// Keluar dari thread UI supaya file exe tidak terkunci saat installer menimpanya.
	w.Dispatch(func() { os.Exit(0) })
}

type ghRelease struct {
	TagName string `json:"tag_name"`
	Assets  []struct {
		Name               string `json:"name"`
		BrowserDownloadURL string `json:"browser_download_url"`
	} `json:"assets"`
}

// latestInstaller mencari installer .exe pada rilis terbaru GitHub.
func latestInstaller() (url, tag string, err error) {
	client := &http.Client{Timeout: 20 * time.Second}
	req, err := http.NewRequest(http.MethodGet,
		"https://api.github.com/repos/"+updateRepo+"/releases/latest", nil)
	if err != nil {
		return "", "", err
	}
	req.Header.Set("User-Agent", "WhatsAppWebClient/"+version)
	req.Header.Set("Accept", "application/vnd.github+json")

	resp, err := client.Do(req)
	if err != nil {
		return "", "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", fmt.Errorf("GitHub: %s", resp.Status)
	}
	var release ghRelease
	if err := json.NewDecoder(resp.Body).Decode(&release); err != nil {
		return "", "", err
	}
	for _, a := range release.Assets {
		if isInstallerAsset(a.Name) {
			return a.BrowserDownloadURL, release.TagName, nil
		}
	}
	return "", "", nil
}

// isInstallerAsset memilih aset installer. .exe aplikasi juga ikut diunggah ke
// rilis dan tidak boleh dijalankan sebagai installer.
func isInstallerAsset(name string) bool {
	name = strings.ToLower(name)
	return strings.HasPrefix(name, "whatsappwebclient-setup") && strings.HasSuffix(name, ".exe")
}

// download menyimpan installer ke folder sementara Windows dan mengembalikan path-nya.
func download(url, tag string) (string, error) {
	client := &http.Client{Timeout: 15 * time.Minute}
	resp, err := client.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("unduhan: %s", resp.Status)
	}

	dir, err := os.MkdirTemp("", "wa-setup")
	if err != nil {
		return "", err
	}
	path := filepath.Join(dir, fmt.Sprintf("WhatsAppWebClient-Setup-%s.exe", strings.TrimPrefix(tag, "v")))
	out, err := os.Create(path)
	if err != nil {
		return "", err
	}
	if _, err := io.Copy(out, resp.Body); err != nil {
		out.Close()
		return "", err
	}
	if err := out.Close(); err != nil {
		return "", err
	}
	return path, nil
}

// messageBox dialog bawaan Windows; aman dipanggil dari goroutine mana pun.
func messageBox(text, caption string, flags uint32) uintptr {
	title, _ := syscall.UTF16PtrFromString(caption)
	body, _ := syscall.UTF16PtrFromString(text)
	result, _, _ := procMessageBoxW.Call(
		0,
		uintptr(unsafe.Pointer(body)),
		uintptr(unsafe.Pointer(title)),
		uintptr(flags),
	)
	return result
}
