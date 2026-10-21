//go:build windows

package main

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"

	"github.com/jchv/go-webview2"
)

// accountsPayload mengubah daftar akun menjadi JSON untuk UI.
func accountsPayload(f accountsFile) string {
	data, err := json.Marshal(f)
	if err != nil {
		return `{"accounts":[],"active":""}`
	}
	return string(data)
}

// runManager membuka jendela pengelolaan akun (WebView2) dan memblokir sampai
// jendela ditutup. Jendela ini yang menambah, mengganti nama, dan menghapus akun.
func runManager(store AccountStore) {
	base, err := appDataDir()
	if err != nil {
		messageBox("Folder data aplikasi tidak dapat ditemukan:\n"+err.Error(), appName, mbIconWarning)
		return
	}
	dataPath := filepath.Join(base, "manager")
	if err := os.MkdirAll(dataPath, 0o700); err != nil {
		messageBox("Folder data aplikasi tidak dapat dibuat:\n"+err.Error(), appName, mbIconWarning)
		return
	}

	w := webview2.NewWithOptions(webview2.WebViewOptions{
		DataPath:  dataPath,
		AutoFocus: true,
		WindowOptions: webview2.WindowOptions{
			Title:  "Akun WhatsApp",
			Width:  460,
			Height: 640,
			Center: true,
		},
	})
	if w == nil {
		// Tanpa WebView2 tidak ada jendela management; WhatsApp tetap bisa
		// dibuka lewat Chrome.
		messageBox("Microsoft Edge WebView2 Runtime tidak ditemukan.\n\n"+
			"Kelola akun membutuhkan WebView2. WhatsApp tetap bisa dibuka lewat Chrome.",
			appName, mbIconWarning)
		return
	}
	defer w.Destroy()

	bind := func(name string, fn interface{}) {
		if err := w.Bind(name, fn); err != nil {
			log.Printf("bind %s gagal: %v", name, err)
		}
	}

	bind("listAccounts", func() (string, error) {
		f, err := store.Load()
		if err != nil {
			return "", err
		}
		return accountsPayload(f), nil
	})

	bind("addAccount", func(label string) (string, error) {
		f, err := store.Load()
		if err != nil {
			return "", err
		}
		f, _, err = addAccount(f, label)
		if err != nil {
			return "", err
		}
		if err := store.Save(f); err != nil {
			return "", err
		}
		return accountsPayload(f), nil
	})

	bind("renameAccount", func(id string, label string) (string, error) {
		f, err := store.Load()
		if err != nil {
			return "", err
		}
		f, err = renameAccount(f, id, label)
		if err != nil {
			return "", err
		}
		if err := store.Save(f); err != nil {
			return "", err
		}
		return accountsPayload(f), nil
	})

	bind("deleteAccount", func(id string) (string, error) {
		f, err := store.Load()
		if err != nil {
			return "", err
		}
		acc := accountByID(f, id)
		if acc == nil {
			return "", errors.New("akun tidak ditemukan")
		}
		f, err = removeAccount(f, id)
		if err != nil {
			return "", err
		}
		if err := store.Save(f); err != nil {
			return "", err
		}
		// Data profil ikut dihapus: sesi WhatsApp akun itu hilang, sama seperti
		// hapus akun di app Android.
		_ = os.RemoveAll(chromeProfilePath(base, *acc))
		_ = os.RemoveAll(webviewDataPath(base, *acc))
		return accountsPayload(f), nil
	})

	bind("openAccount", func(id string) (string, error) {
		f, err := store.Load()
		if err != nil {
			return "", err
		}
		if accountByID(f, id) == nil {
			return "", errors.New("akun tidak ditemukan")
		}
		f.Active = id
		if err := store.Save(f); err != nil {
			return "", err
		}
		openAccount(f, id)
		return accountsPayload(f), nil
	})

	w.SetHtml(managerHTML)
	w.Run()
}

// managerHTML = halaman management akun (gaya claymorphism, sama dengan app Android).
const managerHTML = `<!doctype html>
<html lang="id">
<head>
<meta charset="utf-8">
<title>Akun WhatsApp</title>
<style>
  :root {
    --bg: #D6E8FF; --surface: #F0F7FF; --hi: #FFFFFF; --shade: rgba(20,36,48,.10);
    --text: #2A3A4A; --muted: #5A6A7A; --primary: #5B9BD5; --accent: #F4A8C8; --divider: #C4D4E8;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #1E2A3A; --surface: #2A3A4A; --hi: #3B4F66; --shade: rgba(0,0,0,.25);
      --text: #E8F0FF; --muted: #A0B0C0; --primary: #4A7FB0; --accent: #E089A8; --divider: #3A4A5A;
    }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 18px; background: var(--bg); color: var(--text);
    font-family: "Segoe UI", system-ui, sans-serif; font-size: 14px;
  }
  .card {
    background: var(--surface); border-radius: 20px; padding: 18px;
    box-shadow: 7px 7px 16px var(--shade), -7px -7px 16px var(--hi);
  }
  h1 { margin: 0 0 4px; font-size: 20px; }
  .sub { margin: 0 0 14px; color: var(--muted); font-size: 12px; line-height: 1.5; }
  .acct {
    display: flex; align-items: center; gap: 10px; padding: 12px 14px; margin-bottom: 8px;
    border-radius: 14px; background: var(--bg); cursor: pointer;
    box-shadow: inset 3px 3px 7px var(--shade), inset -3px -3px 7px var(--hi);
  }
  .acct.sel { outline: 2px solid var(--primary); }
  .name { font-weight: 600; }
  .tag { margin-left: auto; font-size: 11px; color: var(--muted); }
  .tag.on { color: var(--primary); font-weight: 600; }
  input {
    width: 100%; padding: 10px 12px; margin-bottom: 12px; border: none; border-radius: 12px;
    background: var(--bg); color: var(--text); font: inherit;
    box-shadow: inset 3px 3px 7px var(--shade), inset -3px -3px 7px var(--hi);
  }
  input:focus { outline: 2px solid var(--primary); }
  .row { display: flex; gap: 8px; flex-wrap: wrap; }
  button {
    flex: 1 1 auto; padding: 10px 14px; border: none; border-radius: 12px; cursor: pointer;
    font: inherit; font-weight: 600; color: var(--text); background: var(--surface);
    box-shadow: 4px 4px 10px var(--shade), -4px -4px 10px var(--hi);
  }
  button:disabled { opacity: .45; cursor: default; }
  button.primary { background: var(--primary); color: #fff; }
  button.danger { background: var(--accent); color: #3A2030; }
  .wide { flex-basis: 100%; margin-top: 8px; padding: 13px; }
  .msg { margin-top: 12px; min-height: 18px; font-size: 12px; color: var(--muted); }
  .msg.err { color: var(--accent); }
</style>
</head>
<body>
  <div class="card">
    <h1>Akun WhatsApp</h1>
    <p class="sub">Tiap akun memakai profil browser sendiri, jadi sesi WhatsApp tidak saling bercampur. Maksimal 4 akun.</p>
    <div id="list"></div>
    <input id="name" maxlength="32" placeholder="Nama akun">
    <div class="row">
      <button id="add">Tambah</button>
      <button id="rename">Ubah nama</button>
      <button id="del" class="danger">Hapus</button>
    </div>
    <button id="open" class="primary wide">Buka WhatsApp</button>
    <div id="msg" class="msg"></div>
  </div>
<script>
  var accounts = [], active = "", selected = "", confirming = false;

  function $(id) { return document.getElementById(id); }
  function say(text, err) {
    var el = $("msg");
    el.textContent = text || "";
    el.className = err ? "msg err" : "msg";
  }
  function apply(raw) {
    var data = JSON.parse(raw);
    accounts = data.accounts || [];
    active = data.active || "";
    var still = accounts.some(function (a) { return a.id === selected; });
    if (!still) { selected = active || (accounts.length ? accounts[0].id : ""); }
    render();
  }
  function render() {
    var list = $("list");
    list.innerHTML = "";
    accounts.forEach(function (a) {
      var row = document.createElement("div");
      row.className = "acct" + (a.id === selected ? " sel" : "");
      var name = document.createElement("div");
      name.className = "name";
      name.textContent = a.label;
      var tag = document.createElement("div");
      tag.className = "tag" + (a.id === active ? " on" : "");
      tag.textContent = a.id === active ? "AKTIF" : "pilih";
      row.appendChild(name);
      row.appendChild(tag);
      row.onclick = function () { selected = a.id; confirming = false; render(); };
      list.appendChild(row);
    });
    $("open").disabled = !selected;
    $("rename").disabled = !selected;
    $("del").disabled = !selected;
    $("del").textContent = confirming ? "Yakin hapus?" : "Hapus";
    $("add").disabled = accounts.length >= 4;
  }
  function fail(e) { say((e && e.message) ? e.message : String(e), true); }

  window.listAccounts().then(apply).catch(fail);

  $("add").onclick = function () {
    var name = $("name").value;
    window.addAccount(name).then(function (raw) {
      $("name").value = "";
      apply(raw);
      say("Akun ditambahkan.");
    }).catch(fail);
  };
  $("rename").onclick = function () {
    var name = $("name").value;
    if (!selected) { say("Pilih akun dulu.", true); return; }
    window.renameAccount(selected, name).then(function (raw) {
      $("name").value = "";
      apply(raw);
      say("Nama akun diubah.");
    }).catch(fail);
  };
  $("del").onclick = function () {
    if (!selected) { say("Pilih akun dulu.", true); return; }
    if (!confirming) { confirming = true; render(); say("Klik Hapus sekali lagi untuk menghapus."); return; }
    window.deleteAccount(selected).then(function (raw) {
      confirming = false;
      apply(raw);
      say("Akun dihapus.");
    }).catch(fail);
  };
  $("open").onclick = function () {
    if (!selected) { say("Pilih akun dulu.", true); return; }
    window.openAccount(selected).then(function (raw) {
      apply(raw);
      say("WhatsApp dibuka. Membuka akun lain akan menutup jendela sebelumnya.");
    }).catch(fail);
  };
</script>
</body>
</html>`
