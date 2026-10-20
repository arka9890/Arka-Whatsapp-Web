//go:build windows

// WhatsApp Web Client untuk Windows: web.whatsapp.com di Chrome milik pengguna
// (mode app window, profil terisolasi), jatuh balik ke WebView2 jika Chrome
// tidak terpasang, dan update otomatis dari GitHub Releases untuk keduanya.
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
	// Pembaruan dicek dari latar: dialog tertawar sebelum jendela ada pun, dan
	// exe lama ditutup lewat os.Exit sebelum installer menimpanya.
	updated := make(chan bool, 1)
	go func() { updated <- checkUpdate() }()

	// Jalur utama: Chrome milik pengguna, mode app window dengan profil sendiri.
	if launchChrome() {
		<-updated // tunggu keputusan update supaya proses tidak mati di tengah dialog.
		return
	}
	// Chrome tidak ditemukan: jatuh balik ke WebView2 (mesin Chromium bawaan Windows).
	startWebview()
}

// launchChrome membuka web.whatsapp.com di mode app window Chrome; true bila sukses.
func launchChrome() bool {
	exe := chromePath()
	if exe == "" {
		return false
	}
	profile, err := chromeProfileDir()
	if err != nil || os.MkdirAll(profile, 0o700) != nil {
		return false
	}
	return exec.Command(exe,
		"--app="+homeURL,
		"--user-data-dir="+profile,
		"--no-first-run",
		"--no-default-browser-check",
	).Start() == nil
}

// chromePath mencari chrome.exe di lokasi pemasangan standar, lalu di PATH.
func chromePath() string {
	for _, env := range []string{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"} {
		cand := filepath.Join(os.Getenv(env), "Google", "Chrome", "Application", "chrome.exe")
		if fi, err := os.Stat(cand); err == nil && !fi.IsDir() {
			return cand
		}
	}
	if exe, err := exec.LookPath("chrome.exe"); err == nil {
		return exe
	}
	return ""
}

// chromeProfileDir: profil Chrome (login WhatsApp) di luar folder instalasi,
// terpisah dari profil Chrome harian pengguna dan dari profil WebView2.
func chromeProfileDir() (string, error) {
	base, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, appName, "chrome-profile"), nil
}

// startWebview: jalur fallback bila Chrome tidak terpasang.
func startWebview() {
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
// Mengembalikan true bila installer sudah dijalankan dan proses harus keluar;
// gagal jaringan, tidak ada rilis, atau penolakan pengguna: aplikasi tetap jalan.
func checkUpdate() bool {
	setup, tag, err := latestInstaller()
	if err != nil || setup == "" || !isNewer(tag, version) {
		return false
	}
	prompt := fmt.Sprintf(
		"Versi baru tersedia: %s (terpasang: %s).\n\n"+
			"Instalasi akan dijalankan dan aplikasi ditutup. Lanjutkan?", tag, version)
	if messageBox(prompt, "Perbarui WhatsApp Web Client", mbYesNo|mbIconInformation) != idYes {
		return false
	}
	file, err := download(setup, tag)
	if err != nil {
		messageBox("Unduhan pembaruan gagal:\n"+err.Error(), "Perbarui WhatsApp Web Client", mbIconWarning)
		return false
	}
	runInstallerAndExit(file)
	return false
}

// runInstallerAndExit meluncurkan installer lalu menutup proses ini supaya
// file exe tidak terkunci saat installer menimpanya. Tidak kembali.
func runInstallerAndExit(file string) {
	if err := exec.Command(file).Start(); err != nil {
		messageBox("Installer tidak dapat dijalankan:\n"+err.Error(), "Perbarui WhatsApp Web Client", mbIconWarning)
		os.Exit(0)
	}
	os.Exit(0)
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
