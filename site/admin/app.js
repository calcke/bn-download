// =====================================================================
// SAVED DATA: locked with your password in this page, stored online
// =====================================================================
function defaultData() {
  return { sign: null, devices: [], ledger: [] };
}
var data = null;

var VAULT = { pw: null, key: null, salt: null, rev: 0, dirty: false, busy: false, blocked: false, timer: null };

function b64(bytes) { var s = ""; for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]); return btoa(s); }
function unb64(s) { var bin = atob(s), out = new Uint8Array(bin.length); for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out; }

function deriveKey(pw, salt) {
  return crypto.subtle.importKey("raw", utf8(pw), "PBKDF2", false, ["deriveKey"]).then(function (base) {
    return crypto.subtle.deriveKey({ name: "PBKDF2", salt: salt, iterations: 250000, hash: "SHA-256" },
      base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
  });
}
function sealData() {
  var iv = randomBytes(12);
  return crypto.subtle.encrypt({ name: "AES-GCM", iv: iv }, VAULT.key, utf8(JSON.stringify(data))).then(function (ct) {
    return { v: 1, salt: b64(VAULT.salt), iv: b64(iv), data: b64(new Uint8Array(ct)) };
  });
}
function openVault(v) {
  return crypto.subtle.decrypt({ name: "AES-GCM", iv: unb64(v.iv) }, VAULT.key, unb64(v.data)).then(function (buf) {
    return JSON.parse(new TextDecoder().decode(buf));
  });
}

// When the page is hosted on GitHub Pages there is no server function, so the locked data is kept in THIS browser.
var LOCAL = false;
var LOCAL_KEY = "bn_admin_vault_v1";
function localLoad() { try { var s = localStorage.getItem(LOCAL_KEY); return s ? JSON.parse(s) : null; } catch (e) { return null; } }
function savedText() { return LOCAL ? "Saved in this browser" : "Saved"; }

function detectMode() {
  return fetch("/api/vault", { method: "POST", cache: "no-store", headers: { "Content-Type": "application/json" }, body: "{}" })
    .then(function (r) { LOCAL = (r.status === 404 || r.status === 405); }, function () { LOCAL = true; })
    .then(function () {
      if (!LOCAL) return;
      $("#modeNote").textContent = "Your data is saved in THIS browser only, locked with your password. Download a backup (Backup tab) after you make the app key and after every token.";
      if (!localLoad()) {
        $("#pw").placeholder = "Make a new password";
        $("#pw2").hidden = false;
        $("#lockmsg").textContent = "First time on this browser: choose a password and type it twice. If you already have a backup file, restore it after unlocking.";
      }
    });
}

function api(action, extra) {
  if (LOCAL) {
    if (action === "load") return Promise.resolve({ status: 200, json: { rev: 0, vault: localLoad() } });
    try { localStorage.setItem(LOCAL_KEY, JSON.stringify(extra.vault)); return Promise.resolve({ status: 200, json: { rev: 0 } }); }
    catch (e) { return Promise.resolve({ status: 500, json: {} }); }
  }
  return fetch("/api/vault", {
    method: "POST", cache: "no-store",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(Object.assign({ action: action, password: VAULT.pw }, extra || {}))
  }).then(function (r) {
    return r.json().catch(function () { return {}; }).then(function (j) { return { status: r.status, json: j }; });
  });
}

function setSync(text, cls) { var s = $("#sync"); s.textContent = text; s.className = "sync " + (cls || ""); }

function save() {
  if (VAULT.blocked) return;
  VAULT.dirty = true; setSync("Saving...", "");
  clearTimeout(VAULT.timer); VAULT.timer = setTimeout(push, 500);
}
function push() {
  if (VAULT.busy) { VAULT.timer = setTimeout(push, 400); return; }
  if (!VAULT.dirty || VAULT.blocked) return;
  VAULT.busy = true; VAULT.dirty = false;
  sealData().then(function (vault) {
    return api("save", { baseRev: VAULT.rev, vault: vault });
  }).then(function (r) {
    VAULT.busy = false;
    if (r.status === 200) { VAULT.rev = r.json.rev; setSync(VAULT.dirty ? "Saving..." : savedText(), VAULT.dirty ? "" : "ok"); if (VAULT.dirty) push(); return; }
    if (r.status === 409) {
      VAULT.blocked = true; setSync("NOT SAVED: changed on another device", "bad");
      alert("These details were changed on another device or tab. Your last edit was NOT saved. Press Lock, unlock again, and redo it.");
      return;
    }
    throw new Error("status " + r.status);
  }).catch(function () {
    VAULT.busy = false; VAULT.dirty = true; setSync("NOT SAVED: check your internet. Retrying...", "bad");
    VAULT.timer = setTimeout(push, 5000);
  });
}
window.addEventListener("beforeunload", function (e) { if (VAULT.dirty || VAULT.busy) { e.preventDefault(); e.returnValue = ""; } });

function unlock(pw) {
  VAULT.pw = pw;
  return api("load").then(function (r) {
    if (r.status === 401) throw new Error("Wrong password.");
    if (r.status === 429) throw new Error("Too many wrong tries. Wait 15 minutes and try again.");
    if (r.status === 503) throw new Error("The site has no ADMIN_PASSWORD set yet. See the setup steps.");
    if (r.status !== 200) throw new Error("Could not reach the server (" + r.status + "). Check your internet.");
    VAULT.rev = r.json.rev || 0;
    if (r.json.vault) {
      VAULT.salt = unb64(r.json.vault.salt);
      return deriveKey(pw, VAULT.salt).then(function (k) {
        VAULT.key = k;
        return openVault(r.json.vault).then(function (d) { data = d; data.devices = data.devices || []; data.ledger = data.ledger || []; data.sign = data.sign || null; }, function () {
          throw new Error(LOCAL ? "Wrong password." : "The password was accepted, but your saved data is locked with a different password. Use the password that was set when you first opened this page.");
        });
      });
    }
    VAULT.salt = randomBytes(16);
    return deriveKey(pw, VAULT.salt).then(function (k) { VAULT.key = k; data = defaultData(); save(); });
  });
}

// Lock the page after 15 minutes of no use
var idleTimer;
function bumpIdle() { clearTimeout(idleTimer); idleTimer = setTimeout(function () { location.reload(); }, 15 * 60 * 1000); }
["click", "keydown", "touchstart", "mousemove"].forEach(function (ev) { document.addEventListener(ev, bumpIdle, { passive: true }); });

// =====================================================================
// SMALL HELPERS
// =====================================================================
function $(sel, root) { return (root || document).querySelector(sel); }
function el(tag, attrs, kids) {
  var n = document.createElement(tag);
  Object.keys(attrs || {}).forEach(function (k) {
    if (k === "text") n.textContent = attrs[k];
    else if (k === "class") n.className = attrs[k];
    else if (k.slice(0, 2) === "on") n.addEventListener(k.slice(2), attrs[k]);
    else if (k === "value") n.value = attrs[k];
    else n.setAttribute(k, attrs[k]);
  });
  (kids || []).forEach(function (c) { n.appendChild(typeof c === "string" ? document.createTextNode(c) : c); });
  return n;
}
function toast(msg) {
  var t = $("#toast"); t.textContent = msg; t.classList.add("show");
  clearTimeout(toast.h); toast.h = setTimeout(function () { t.classList.remove("show"); }, 1800);
}
function copy(text) {
  function fallback() {
    var ta = el("textarea", {value: text}); document.body.appendChild(ta); ta.select();
    try { document.execCommand("copy"); toast("Copied"); } catch (e) { toast("Select the text and press Ctrl+C"); }
    ta.remove();
  }
  if (navigator.clipboard && window.isSecureContext) {
    navigator.clipboard.writeText(text).then(function () { toast("Copied"); }, fallback);
  } else fallback();
}
function download(name, text) {
  var a = el("a", {href: URL.createObjectURL(new Blob([text], {type: "text/plain"})), download: name});
  document.body.appendChild(a); a.click(); a.remove();
}
function stamp(t) { return new Date(t).toLocaleString(); }

// =====================================================================
// TAB 1: APP KEY  (made once; the phone app needs its public half)
// =====================================================================
function renderKey() {
  var root = $("#tab-key"); root.innerHTML = "";
  if (!data.sign) {
    root.appendChild(el("div", {class: "card"}, [
      el("div", {class: "label", text: "Make your app key (once)"}),
      el("div", {class: "muted", text: "This makes a matching pair: a private half that stays in this page (locked with your password), and a public half you put inside the phone app. The app uses the public half to check that a credit token really came from you. Nobody can make tokens without the private half, even if they take the app apart."}),
      el("div", {class: "row", style: "margin-top:10px"}, [el("button", {class: "primary", text: "Make app key", onclick: function (ev) {
        if (!confirm("Make the app key now? Do this only ONCE. A second key would make every app already built stop accepting your tokens.")) return;
        ev.target.disabled = true;
        generateSigningKey().then(function (k) { data.sign = k; save(); renderKey(); renderCredits(); toast("App key made"); },
          function () { ev.target.disabled = false; alert("This browser could not make the key. Try Chrome or Edge."); });
      }})])
    ]));
    return;
  }
  var pubBox = el("div", {class: "key", text: data.sign.pub});
  root.appendChild(el("div", {class: "card"}, [
    el("div", {class: "label", text: "Your app key is ready"}),
    el("div", {class: "muted", text: "Public half (safe to show, it goes into the app):"}),
    pubBox,
    el("div", {class: "row"}, [el("button", {class: "primary", text: "Copy public key", onclick: function () { copy(data.sign.pub); }})])
  ]));
  root.appendChild(el("div", {class: "card"}, [
    el("div", {class: "label", text: "Put it in the app (once)"}),
    el("ol", {}, [
      el("li", {text: "Copy the public key above."}),
      el("li", {text: "On GitHub open android/app/src/main/assets/public_key.txt, click the pencil (edit), delete what is there, paste the key as ONE line, and commit."}),
      el("li", {text: "That starts the APK build. Install the new APK on the phone. From then on, tokens from tab 2 work."})
    ]),
    el("div", {class: "muted", text: "If you ever lose this key (no backup and the saved data is gone), you would have to make a new one and rebuild the app. Download a backup in the Backup tab now."})
  ]));
}

// =====================================================================
// TAB 2: CREDITS
// =====================================================================
function renderCredits() {
  var root = $("#tab-credits"); root.innerHTML = "";
  if (!data.sign) {
    root.appendChild(el("div", {class: "card"}, [el("div", {class: "label", text: "No app key yet"}),
      el("div", {class: "muted", text: "Go to tab 1 and make the app key first."})]));
    return;
  }
  var nameIn = el("input", {placeholder: "Name, e.g. Wanjiku shop", maxlength: "40"});
  var idIn = el("input", {placeholder: "Device ID from the app, e.g. 7K2F9-QXA3B", maxlength: "14", style: "text-transform:uppercase"});
  var saved = el("select", {onchange: function (ev) {
    var d = data.devices.filter(function (x) { return x.id === ev.target.value; })[0];
    if (d) { nameIn.value = d.name; idIn.value = d.deviceId; }
  }}, [el("option", {value: "", text: data.devices.length ? "Pick a saved phone..." : "No saved phones yet"})].concat(
    data.devices.map(function (d) { return el("option", {value: d.id, text: d.name + "  (" + d.deviceId + ")"}); })));
  var tokIn = el("input", {type: "number", min: "1", value: "1000", inputmode: "numeric"});
  var chips = el("div", {class: "chips"}, [100, 500, 1000, 5000].map(function (n) {
    return el("button", {class: "quiet", text: String(n), onclick: function () { tokIn.value = n; }});
  }));
  var out = el("div", {style: "margin-top:10px"});

  function show(msg, serial) {
    out.innerHTML = "";
    out.appendChild(el("textarea", {readonly: "readonly", value: msg, onclick: function (e) { e.target.select(); }}));
    out.appendChild(el("div", {class: "muted", text: msg.length + " characters  (serial " + serial + ")"}));
    out.appendChild(el("div", {class: "row", style: "margin-top:8px"}, [
      el("button", {class: "primary", text: "Copy token", onclick: function () { copy(msg); }}),
      el("button", {text: "Share on WhatsApp", onclick: function () { window.open("https://wa.me/?text=" + encodeURIComponent(msg), "_blank", "noopener"); }})
    ]));
    out.appendChild(el("div", {class: "muted", style: "margin-top:8px", text: "Send this exactly as it is. The merchant copies it, opens bn, pastes it in the ADD CREDITS box and taps ADD CREDITS. It works once, on that phone only."}));
  }

  root.appendChild(el("div", {class: "card"}, [
    el("div", {class: "label", text: "Make a credit token"}),
    el("div", {class: "muted", text: "One credit per shilling of sale. A phone with 0 credits will not dial. The token holds only the credits and the phone's Device ID: the merchant sets their own offers in the app."}),
    el("div", {style: "margin-top:10px"}, [saved]),
    el("div", {class: "row", style: "margin-top:8px"}, [nameIn, idIn]),
    chips,
    el("div", {class: "row"}, [tokIn, el("button", {class: "primary", text: "Make token", onclick: function () {
      var name = nameIn.value.trim(), serial = makeSerial(), n = Math.floor(Number(tokIn.value)), id;
      out.innerHTML = "";
      try { id = normalizeDeviceId(idIn.value); } catch (e) { out.appendChild(el("div", {class: "bad", text: e.message})); return; }
      if (!name) { out.appendChild(el("div", {class: "bad", text: "Type a name for this phone so you can find it later."})); return; }
      makeVoucher(serial, n, id, data.sign.privJwk).then(function (code) {
        var d = data.devices.filter(function (x) { return x.deviceId === id; })[0];
        if (d) d.name = name; else data.devices.push({id: "d" + Date.now(), name: name, deviceId: id});
        data.ledger.unshift({t: Date.now(), phone: name, deviceId: id, serial: serial, tokens: n});
        save(); show(code, serial); renderLedger();
      }, function (e) { out.appendChild(el("div", {class: "bad", text: "Cannot make token: " + e.message})); });
    }})]),
    out
  ]));
  root.appendChild(el("div", {class: "card", id: "ledger"}));
  renderLedger();
}
function renderLedger() {
  var box = $("#ledger"); if (!box) return;
  box.innerHTML = ""; box.appendChild(el("div", {class: "label", text: "Tokens you have made"}));
  if (!data.ledger.length) { box.appendChild(el("div", {class: "muted", text: "None yet."})); return; }
  data.ledger.slice(0, 20).forEach(function (l) {
    box.appendChild(el("div", {class: "muted", text: stamp(l.t) + "  ·  " + l.phone + "  ·  " + l.tokens + " credits  ·  " + l.serial}));
  });
}

// =====================================================================
// TAB 3: BACKUP
// =====================================================================
function renderBackup() {
  var root = $("#tab-backup"); root.innerHTML = "";
  var restoreIn = el("input", {type: "file", accept: ".json", style: "display:none", onchange: function (ev) {
    var f = ev.target.files[0]; if (!f) return;
    var r = new FileReader();
    r.onload = function () {
      try {
        var d = JSON.parse(r.result);
        if (typeof d !== "object" || !d || !(d.sign || d.ledger || d.phones)) throw 0;
        if (!confirm("Replace everything in this page with the backup?")) return;
        d.devices = d.devices || []; d.ledger = d.ledger || []; d.sign = d.sign || null;
        data = d; save(); renderAll(); toast("Backup restored");
      } catch (e) { alert("That is not a bn admin backup file."); }
    };
    r.readAsText(f);
  }});
  root.appendChild(el("div", {class: "card"}, [
    el("div", {class: "label", text: "Back up your app key"}),
    el("div", {class: "muted", text: (LOCAL ? "Your data is saved in this browser only. If you clear the browser data or use another computer it is gone, so the backup file is your only safety net. " : "Your data is stored online, locked with your password. ") + "Download a backup after making the app key and after every token, and keep it somewhere private (a flash disk and your own email). The backup file is NOT locked and contains your private key, so never share it or upload it to GitHub."}),
    el("div", {class: "row", style: "margin-top:10px"}, [
      el("button", {class: "primary", text: "Download backup", onclick: function () {
        download("bn-admin-backup-" + new Date().toISOString().slice(0, 10) + ".json", JSON.stringify(data, null, 2));
      }}),
      el("button", {class: "quiet", text: "Restore from backup", onclick: function () { restoreIn.click(); }})
    ]), restoreIn
  ]));
}

// =====================================================================
function renderAll() { renderKey(); renderCredits(); renderBackup(); }
document.querySelectorAll("nav button").forEach(function (b) {
  b.addEventListener("click", function () {
    document.querySelectorAll("nav button").forEach(function (x) { x.classList.toggle("on", x === b); });
    ["key", "credits", "backup"].forEach(function (t) { $("#tab-" + t).hidden = (t !== b.dataset.tab); });
    if (b.dataset.tab === "credits") renderCredits();
  });
});

function startApp() {
  $("#lock").hidden = true; $("#app").hidden = false;
  setSync(VAULT.dirty ? "Saving..." : savedText(), VAULT.dirty ? "" : "ok");
  renderAll(); bumpIdle();
  if (data.sign) { document.querySelector('nav button[data-tab="credits"]').click(); }
}
$("#lockbtn").addEventListener("click", function () {
  if ((VAULT.dirty || VAULT.busy) && !confirm("Some changes are still saving. Lock anyway?")) return;
  VAULT.dirty = false; location.reload();
});
function tryUnlock() {
  var pw = $("#pw").value, msg = $("#lockmsg"), btn = $("#go");
  if (!pw) { msg.textContent = "Type your password."; msg.className = "bad"; return; }
  btn.disabled = true; msg.className = "muted"; msg.textContent = "Unlocking...";
  modeReady.then(function () {
    if (LOCAL && !localLoad()) {
      if (pw.length < 8) throw new Error("Use at least 8 characters.");
      if (pw !== $("#pw2").value) throw new Error("The two passwords are not the same.");
    }
    return unlock(pw);
  }).then(function () { $("#pw").value = ""; $("#pw2").value = ""; startApp(); }, function (e) {
    VAULT.pw = null; msg.textContent = e.message; msg.className = "bad";
  }).then(function () { btn.disabled = false; });
}
$("#go").addEventListener("click", tryUnlock);
$("#pw").addEventListener("keydown", function (e) { if (e.key === "Enter") tryUnlock(); });
$("#pw2").addEventListener("keydown", function (e) { if (e.key === "Enter") tryUnlock(); });
var modeReady = detectMode();
$("#pw").focus();
