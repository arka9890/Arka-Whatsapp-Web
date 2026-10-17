/**
 * Tes badge_hook.js tanpa Android: node tools/badge_hook.test.js
 *
 * Hook WhatsApp membaca jumlah unread dari document.title ("(3) WhatsApp"), jadi tes ini
 * cukup memberi judul palsu lalu memutar timer hook dan memeriksa apa yang dikirim ke bridge.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const SRC = fs.readFileSync(
  path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'badge_hook.js'),
  'utf8'
);

let failures = 0;
function check(name, cond, extra) {
  if (cond) {
    console.log('ok   - ' + name);
  } else {
    failures++;
    console.log('FAIL - ' + name + (extra !== undefined ? ' (' + extra + ')' : ''));
  }
}

/** Sandbox dengan timer tertangkap, supaya tes tidak perlu menunggu 2 detik per siklus. */
function makeSandbox(opts) {
  const forwarded = [];
  const timers = [];
  const title = { value: opts.title };
  const document = {
    get title() {
      return title.value;
    }
  };
  const sandbox = {
    console: { log: () => {} },
    document,
    setInterval: (fn) => {
      timers.push(fn);
      return timers.length;
    },
    clearInterval: () => {}
  };
  sandbox.window = sandbox;
  if (opts.bridge !== false) {
    sandbox.WhatsAppBridge = {
      postMessage: (raw) => forwarded.push(JSON.parse(String(raw)).count)
    };
  }
  vm.createContext(sandbox);
  vm.runInContext(SRC, sandbox, { filename: 'badge_hook.js' });
  return {
    sandbox,
    forwarded,
    setTitle: (v) => {
      title.value = v;
    },
    tick: () => timers.forEach((fn) => fn()),
    timerCount: () => timers.length,
    rerun: () => vm.runInContext(SRC, sandbox, { filename: 'badge_hook.js' })
  };
}

// 1. Tanpa unread: judul polos -> 0.
const t = makeSandbox({ title: 'WhatsApp' });
check('hook terpasang', t.sandbox.__waBadge && t.sandbox.__waBadge.installed === true);
check('judul polos -> 0', JSON.stringify(t.forwarded) === '[0]', JSON.stringify(t.forwarded));

// 2. Judul ber-unread -> jumlahnya diteruskan.
t.setTitle('(3) WhatsApp');
t.tick();
check('"(3) WhatsApp" -> 3', JSON.stringify(t.forwarded) === '[0,3]', JSON.stringify(t.forwarded));

// 3. Nilai sama tidak dikirim ulang.
t.tick();
check('nilai duplikat diabaikan', JSON.stringify(t.forwarded) === '[0,3]', JSON.stringify(t.forwarded));

// 4. Naik, lalu kembali bersih.
t.setTitle('(12) WhatsApp');
t.tick();
t.setTitle('WhatsApp');
t.tick();
check('naik lalu bersih', JSON.stringify(t.forwarded) === '[0,3,12,0]', JSON.stringify(t.forwarded));

// 5. Judul aneh tidak bikin angka ngawur.
t.setTitle('(abc) WhatsApp');
t.tick();
check('judul rusak -> 0', JSON.stringify(t.forwarded) === '[0,3,12,0]', JSON.stringify(t.forwarded));

// 6. Idempoten: skrip dipasang ulang (onPageStarted + onPageFinished) tidak boleh
//    menambah timer atau mengulang kiriman.
t.rerun();
t.setTitle('(5) WhatsApp');
t.tick();
check('pasang ulang: timer tetap satu', t.timerCount() === 1, t.timerCount());
check('pasang ulang: kiriman tetap sekali', JSON.stringify(t.forwarded) === '[0,3,12,0,5]', JSON.stringify(t.forwarded));

// 7. Tanpa bridge (WebMessageListener tidak didukung) hook harus diam, bukan error.
const noBridge = makeSandbox({ title: '(4) WhatsApp', bridge: false });
noBridge.setTitle('(9) WhatsApp');
noBridge.tick();
check('tanpa bridge tidak error', noBridge.forwarded.length === 0);

console.log(failures ? '\n' + failures + ' tes gagal' : '\nsemua tes lulus');
process.exit(failures ? 1 : 0);
