package main

import (
	"os"
	"path/filepath"
	"testing"
)

func tempStore(t *testing.T) AccountStore {
	t.Helper()
	return AccountStore{Path: filepath.Join(t.TempDir(), "accounts.json")}
}

func TestLoadMissingFileIsEmpty(t *testing.T) {
	f, err := tempStore(t).Load()
	if err != nil {
		t.Fatalf("Load() error = %v, want nil", err)
	}
	if len(f.Accounts) != 0 {
		t.Errorf("Load() accounts = %d, want 0", len(f.Accounts))
	}
}

func TestSaveLoadRoundTrip(t *testing.T) {
	store := tempStore(t)
	f, _, err := addAccount(accountsFile{}, "Akun 1")
	if err != nil {
		t.Fatalf("addAccount() error = %v", err)
	}
	f, _, err = addAccount(f, "Akun 2")
	if err != nil {
		t.Fatalf("addAccount() error = %v", err)
	}
	if err := store.Save(f); err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	got, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if len(got.Accounts) != 2 {
		t.Fatalf("Load() accounts = %d, want 2", len(got.Accounts))
	}
	if got.Accounts[1].Label != "Akun 2" {
		t.Errorf("label = %q, want %q", got.Accounts[1].Label, "Akun 2")
	}
	if got.Active != got.Accounts[1].ID {
		t.Errorf("active = %q, want %q (akun terakhir yang ditambah)", got.Active, got.Accounts[1].ID)
	}
}

func TestFirstAccountKeepsLegacyProfile(t *testing.T) {
	base := t.TempDir()
	f, first, err := addAccount(accountsFile{}, "Akun 1")
	if err != nil {
		t.Fatalf("addAccount() error = %v", err)
	}
	if first.Profile != "" {
		t.Errorf("profil akun pertama = %q, want kosong (path lama)", first.Profile)
	}
	f, second, err := addAccount(f, "Akun 2")
	if err != nil {
		t.Fatalf("addAccount() error = %v", err)
	}
	if second.Profile == "" {
		t.Error("profil akun kedua kosong, want direktori terpisah")
	}
	if chromeProfilePath(base, first) == chromeProfilePath(base, second) {
		t.Error("dua akun berbagi direktori Chrome, want terpisah")
	}
	if webviewDataPath(base, first) == webviewDataPath(base, second) {
		t.Error("dua akun berbagi DataPath WebView2, want terpisah")
	}
	// Path lama harus tetap sama supaya login yang sudah ada tidak hilang.
	if got, want := chromeProfilePath(base, first), filepath.Join(base, "chrome-profile"); got != want {
		t.Errorf("chromeProfilePath() = %q, want %q", got, want)
	}
	if got, want := webviewDataPath(base, first), filepath.Join(base, "profile"); got != want {
		t.Errorf("webviewDataPath() = %q, want %q", got, want)
	}
}

func TestAddAccountLimit(t *testing.T) {
	f := accountsFile{}
	for i := 0; i < MaxAccounts; i++ {
		var err error
		f, _, err = addAccount(f, "Akun")
		if err != nil {
			t.Fatalf("addAccount() #%d error = %v", i+1, err)
		}
	}
	if _, _, err := addAccount(f, "Akun"); err == nil {
		t.Error("addAccount() di atas batas = nil error, want error")
	}
}

func TestNormalizeLabel(t *testing.T) {
	if _, err := normalizeLabel("   "); err == nil {
		t.Error("normalizeLabel(kosong) = nil error, want error")
	}
	got, err := normalizeLabel("  Kerja  ")
	if err != nil {
		t.Fatalf("normalizeLabel() error = %v", err)
	}
	if got != "Kerja" {
		t.Errorf("normalizeLabel() = %q, want %q", got, "Kerja")
	}
	long := make([]rune, MaxLabelLength+10)
	for i := range long {
		long[i] = 'a'
	}
	got, err = normalizeLabel(string(long))
	if err != nil {
		t.Fatalf("normalizeLabel() error = %v", err)
	}
	if len([]rune(got)) != MaxLabelLength {
		t.Errorf("panjang label = %d, want %d", len([]rune(got)), MaxLabelLength)
	}
}

func TestRenameKeepsProfile(t *testing.T) {
	f, acc, err := addAccount(accountsFile{}, "Satu")
	if err != nil {
		t.Fatalf("addAccount() error = %v", err)
	}
	f, err = renameAccount(f, acc.ID, "  Dua  ")
	if err != nil {
		t.Fatalf("renameAccount() error = %v", err)
	}
	got := accountByID(f, acc.ID)
	if got == nil {
		t.Fatal("akun hilang setelah rename")
	}
	if got.Label != "Dua" {
		t.Errorf("label = %q, want %q", got.Label, "Dua")
	}
	if got.ID != acc.ID || got.Profile != acc.Profile {
		t.Errorf("id/profil berubah: %q/%q, want %q/%q", got.ID, got.Profile, acc.ID, acc.Profile)
	}
	if _, err := renameAccount(f, "tidak-ada", "X"); err == nil {
		t.Error("renameAccount(id asing) = nil error, want error")
	}
}

func TestRemoveAccountMovesActive(t *testing.T) {
	f, first, err := addAccount(accountsFile{}, "Satu")
	if err != nil {
		t.Fatalf("addAccount() error = %v", err)
	}
	f, second, err := addAccount(f, "Dua")
	if err != nil {
		t.Fatalf("addAccount() error = %v", err)
	}
	if f.Active != second.ID {
		t.Fatalf("active = %q, want akun terakhir", f.Active)
	}
	f, err = removeAccount(f, second.ID)
	if err != nil {
		t.Fatalf("removeAccount() error = %v", err)
	}
	if len(f.Accounts) != 1 {
		t.Fatalf("akun tersisa = %d, want 1", len(f.Accounts))
	}
	if f.Active != first.ID {
		t.Errorf("active = %q, want %q", f.Active, first.ID)
	}
	if _, err := removeAccount(f, "tidak-ada"); err == nil {
		t.Error("removeAccount(id asing) = nil error, want error")
	}
}

func TestLoadDropsBrokenEntriesAndFixesActive(t *testing.T) {
	store := tempStore(t)
	raw := `{"accounts":[{"id":"a","label":"Satu","profile":""},{"id":"","label":""},{"id":"c"}],"active":"hilang"}`
	if err := os.WriteFile(store.Path, []byte(raw), 0o600); err != nil {
		t.Fatalf("WriteFile() error = %v", err)
	}
	f, err := store.Load()
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if len(f.Accounts) != 1 {
		t.Fatalf("akun tersimpan = %d, want 1 (entri rusak dibuang)", len(f.Accounts))
	}
	if f.Active != "a" {
		t.Errorf("active = %q, want %q (aktif menunjuk akun tak dikenal)", f.Active, "a")
	}
}
