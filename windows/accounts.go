package main

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	// appName = folder data aplikasi di %LocalAppData%.
	appName = "WhatsAppWebClient"

	// MaxAccounts = batas jumlah akun, selaras dengan app Android.
	MaxAccounts = 4

	// MaxLabelLength = panjang maksimum nama akun (karakter, bukan byte).
	MaxLabelLength = 32
)

// Account adalah satu sesi WhatsApp: label tampilan + direktori profil terpisah.
// Profile kosong = profil bawaan (dihapus dari versi lama), jadi login yang
// sudah ada tidak hilang saat fitur akun diperkenalkan.
type Account struct {
	ID      string `json:"id"`
	Label   string `json:"label"`
	Profile string `json:"profile"`
	Created int64  `json:"created"`
}

// accountsFile adalah isi accounts.json: daftar akun + akun yang aktif.
type accountsFile struct {
	Accounts []Account `json:"accounts"`
	Active   string    `json:"active"`
}

// AccountStore membaca dan menulis daftar akun di accounts.json.
type AccountStore struct {
	Path string
}

// Load membaca daftar akun. Berkas yang belum ada = belum ada akun (bukan error).
// Entri tidak lengkap dibuang, bukan menggagalkan seluruh daftar.
func (s AccountStore) Load() (accountsFile, error) {
	data, err := os.ReadFile(s.Path)
	if err != nil {
		if os.IsNotExist(err) {
			return accountsFile{}, nil
		}
		return accountsFile{}, err
	}
	var f accountsFile
	if err := json.Unmarshal(data, &f); err != nil {
		return accountsFile{}, fmt.Errorf("daftar akun tidak terbaca: %w", err)
	}
	clean := make([]Account, 0, len(f.Accounts))
	for _, a := range f.Accounts {
		if a.ID != "" && a.Label != "" {
			clean = append(clean, a)
		}
	}
	f.Accounts = clean
	if accountByID(f, f.Active) == nil {
		f.Active = ""
		if len(clean) > 0 {
			f.Active = clean[0].ID
		}
	}
	return f, nil
}

// Save menulis daftar akun ke disk (atomik: tulis sementara lalu rename).
func (s AccountStore) Save(f accountsFile) error {
	if err := os.MkdirAll(filepath.Dir(s.Path), 0o700); err != nil {
		return err
	}
	data, err := json.MarshalIndent(f, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.Path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.Path)
}

// accountByID mencari akun; nil bila id tidak dikenal.
func accountByID(f accountsFile, id string) *Account {
	for i := range f.Accounts {
		if f.Accounts[i].ID == id {
			return &f.Accounts[i]
		}
	}
	return nil
}

// normalizeLabel membersihkan nama akun: trim, wajib non-kosong, dipotong pada batas karakter.
func normalizeLabel(raw string) (string, error) {
	label := strings.TrimSpace(raw)
	if label == "" {
		return "", errors.New("nama akun wajib diisi")
	}
	if r := []rune(label); len(r) > MaxLabelLength {
		label = string(r[:MaxLabelLength])
	}
	return label, nil
}

// addAccount menambah akun baru. Akun pertama memakai profil bawaan (lama),
// akun berikutnya memakai direktori profil sendiri agar sesinya tidak bercampur.
func addAccount(f accountsFile, rawLabel string) (accountsFile, Account, error) {
	if len(f.Accounts) >= MaxAccounts {
		return f, Account{}, fmt.Errorf("maksimal %d akun", MaxAccounts)
	}
	label, err := normalizeLabel(rawLabel)
	if err != nil {
		return f, Account{}, err
	}
	id, err := newAccountID()
	if err != nil {
		return f, Account{}, err
	}
	acc := Account{ID: id, Label: label, Created: time.Now().Unix()}
	if len(f.Accounts) > 0 {
		acc.Profile = id
	}
	f.Accounts = append(f.Accounts, acc)
	f.Active = acc.ID
	return f, acc, nil
}

// renameAccount mengganti label akun; id dan profil tidak berubah, jadi sesi login utuh.
func renameAccount(f accountsFile, id, rawLabel string) (accountsFile, error) {
	label, err := normalizeLabel(rawLabel)
	if err != nil {
		return f, err
	}
	acc := accountByID(f, id)
	if acc == nil {
		return f, errors.New("akun tidak ditemukan")
	}
	acc.Label = label
	return f, nil
}

// removeAccount menghapus akun; akun aktif pindah ke sisa pertama bila perlu.
func removeAccount(f accountsFile, id string) (accountsFile, error) {
	idx := -1
	for i, a := range f.Accounts {
		if a.ID == id {
			idx = i
			break
		}
	}
	if idx < 0 {
		return f, errors.New("akun tidak ditemukan")
	}
	f.Accounts = append(f.Accounts[:idx], f.Accounts[idx+1:]...)
	if f.Active == id {
		f.Active = ""
		if len(f.Accounts) > 0 {
			f.Active = f.Accounts[0].ID
		}
	}
	return f, nil
}

// newAccountID membuat id acak yang aman untuk nama direktori.
func newAccountID() (string, error) {
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

// appDataDir: folder data aplikasi (%LocalAppData%\WhatsAppWebClient).
func appDataDir() (string, error) {
	base, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(base, appName), nil
}

// accountsPath: lokasi accounts.json.
func accountsPath(base string) string {
	return filepath.Join(base, "accounts.json")
}

// chromeProfilePath: direktori --user-data-dir Chrome untuk sebuah akun.
// Akun tanpa profil memakai path lama supaya login yang sudah ada tidak hilang.
func chromeProfilePath(base string, a Account) string {
	if a.Profile == "" {
		return filepath.Join(base, "chrome-profile")
	}
	return filepath.Join(base, "chrome-profile-"+a.Profile)
}

// webviewDataPath: DataPath WebView2 untuk sebuah akun.
func webviewDataPath(base string, a Account) string {
	if a.Profile == "" {
		return filepath.Join(base, "profile")
	}
	return filepath.Join(base, "profile-"+a.Profile)
}
