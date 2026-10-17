/**
 * Bridge jumlah chat belum terbaca (WhatsApp Web -> Android).
 *
 * WhatsApp Web menaruh jumlah chat unread di judul dokumen dengan format "(3) WhatsApp",
 * dan kembali ke "WhatsApp" saat semuanya sudah dibaca. Judul itu yang diteruskan ke sisi
 * Android; tidak ada markup internal WhatsApp yang diintip, jadi perubahan DOM mereka tidak
 * mematahkan hook ini.
 *
 * Dipasang lewat evaluateJavascript (onPageStarted + onPageFinished), bukan dengan menulis
 * ulang HTML: WhatsApp Web memakai service worker, jadi dokumen bisa dilayani dari cache dan
 * penyisipan ke body respons jaringan tidak bisa diandalkan.
 *
 * Judul diperiksa tiap 2 detik; nilainya hanya dikirim saat berubah.
 */
(function () {
  'use strict';

  if (window.__waBadge && window.__waBadge.installed) {
    return;
  }

  var state = window.__waBadge = { installed: true, count: -1 };

  // Native side exposes this object only to the exact HTTPS WhatsApp Web origin
  // through AndroidX WebMessageListener; it is not a JavaScriptInterface.
  function postNative(payload) {
    try {
      var bridge = window.WhatsAppBridge;
      if (bridge && typeof bridge.postMessage === 'function') {
        bridge.postMessage(JSON.stringify(payload));
        return true;
      }
    } catch (e) {}
    return false;
  }

  function forward(n) {
    n = Math.max(0, n | 0);
    if (state.count === n) return;
    state.count = n;
    postNative({ type: 'unread', count: n });
  }

  function readTitle() {
    var match = /\((\d+)\)/.exec(document.title || '');
    return match ? parseInt(match[1], 10) : 0;
  }

  setInterval(function () {
    forward(readTitle());
  }, 2000);
  forward(readTitle());
})();
