// OrbitTuner portal: progressive enhancement only. Every page works without this script.
// Served from /portal/assets/portal.js under a script-src 'self' CSP. No dependencies.
(function () {
  "use strict";

  var NBSP = "\u00a0";

  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  // ---------------------------------------------------------------------------
  // ChordPro preview. Mirrors src/chordpro/parse.ts and src/portal/Sheet.tsx.
  // ---------------------------------------------------------------------------
  var MAX_DOC = 200000, MAX_LINE = 2000;
  var DIRECTIVE_RE = /^\{\s*([A-Za-z_][\w-]*)(?:\s*:\s*|\s+)?([^}]*)\}$/;
  var ALIASES = { t: "title", st: "subtitle" };
  var COMMENTS = { c: 1, comment: 1, ci: 1, comment_italic: 1, cb: 1, comment_box: 1 };
  var META = { title: 1, subtitle: 1, artist: 1, composer: 1, lyricist: 1, album: 1, year: 1, key: 1, tempo: 1, time: 1, capo: 1, duration: 1, copyright: 1 };
  var SECTION_START = { start_of_verse: "verse", sov: "verse", start_of_chorus: "chorus", soc: "chorus", start_of_bridge: "bridge", sob: "bridge", start_of_tab: "tab", sot: "tab" };
  var SECTION_END = { end_of_verse: 1, eov: 1, end_of_chorus: 1, eoc: 1, end_of_bridge: 1, eob: 1, end_of_tab: 1, eot: 1 };
  var LABELS = { verse: "Verse", chorus: "Chorus", bridge: "Bridge", tab: "Tab", none: "" };

  function segmentsOf(raw) {
    var segs = [], last = 0, pending, m, re = /\[([^\]]*)\]/g;
    while ((m = re.exec(raw))) {
      var text = raw.slice(last, m.index);
      if (pending !== undefined || text !== "") segs.push(pending !== undefined ? { chord: pending, text: text } : { text: text });
      pending = m[1].trim() || undefined;
      last = m.index + m[0].length;
    }
    var tail = raw.slice(last);
    if (pending !== undefined) segs.push({ chord: pending, text: tail });
    else if (tail !== "" || segs.length === 0) segs.push({ text: tail });
    return segs;
  }

  function parse(src) {
    var meta = {}, sections = [], current = null;
    function target() {
      if (!current) { current = { type: "none", lines: [] }; sections.push(current); }
      return current;
    }
    var lines = String(src).slice(0, MAX_DOC).split(/\r\n|\r|\n/);
    for (var i = 0; i < lines.length; i++) {
      var raw = lines[i].length > MAX_LINE ? lines[i].slice(0, MAX_LINE) : lines[i];
      var inTab = current && current.type === "tab";
      var trimmed = raw.trim();
      var dm = trimmed.charAt(0) === "{" && trimmed.charAt(trimmed.length - 1) === "}" ? DIRECTIVE_RE.exec(trimmed) : null;
      if (dm) {
        var name = dm[1].toLowerCase(), value = dm[2].trim();
        name = ALIASES[name] || name;
        if (SECTION_START[name]) {
          current = { type: SECTION_START[name], lines: [] };
          if (value) current.label = value;
          sections.push(current);
        } else if (SECTION_END[name]) {
          current = null;
        } else if (COMMENTS[name]) {
          target().lines.push({ kind: "comment", text: value });
        } else if (META[name]) {
          meta[name] = value;
        } else {
          target().lines.push({ kind: "directive", name: name, value: value });
        }
        continue;
      }
      if (inTab) { current.lines.push({ kind: "tab", text: raw }); continue; }
      if (trimmed === "" || trimmed.charAt(0) === "#") continue;
      target().lines.push({ kind: "lyrics", segments: segmentsOf(raw) });
    }
    sections.forEach(function (sec) {
      if (sec.type !== "tab") return;
      for (;;) {
        var l = sec.lines[sec.lines.length - 1];
        if (l && l.kind === "tab" && l.text.trim() === "") sec.lines.pop();
        else break;
      }
    });
    if (meta.subtitle && !meta.artist) meta.artist = meta.subtitle;
    return { meta: meta, sections: sections };
  }

  // Upload plan for the import page. ZIP archives and JSON backups always travel alone (they
  // cannot be split here, and an error must name them); loose song files share requests of at
  // most maxFiles files and maxBytes bytes.
  function importBatches(files, maxFiles, maxBytes) {
    var out = [], cur = [], bytes = 0;
    files.forEach(function (f) {
      if (/\.(zip|json)$/i.test(f.name)) { out.push([f]); return; }
      if (cur.length && (cur.length >= maxFiles || bytes + f.size > maxBytes)) { out.push(cur); cur = []; bytes = 0; }
      cur.push(f);
      bytes += f.size;
    });
    if (cur.length) out.push(cur);
    return out;
  }

  // ---------------------------------------------------------------------------
  // Link-import polling. Pure: all I/O comes in through `deps`, so the tests drive it.
  // deps: get(id) -> Promise<{ok,status,j}>, schedule(fn, ms), hidden(), onVisible(fn),
  //       render(job), stopped("lost" | "session").
  // ---------------------------------------------------------------------------
  var POLL_MS = 2000, POLL_MAX_MS = 30000, POLL_MAX_MISSES = 6;
  var pollGen = 0;

  /** Delay before the next poll after `misses` consecutive failures: 2, 2, 4, 8, 16, 30, 30 … s. */
  function pollDelay(misses) {
    return misses <= 1 ? POLL_MS : Math.min(POLL_MAX_MS, POLL_MS * Math.pow(2, misses - 1));
  }

  /** Start polling job `id`. Starting another poller (any job) stops this one. */
  function pollJob(id, deps) {
    var gen = ++pollGen;
    var misses = 0;
    var live = function () { return gen === pollGen; };
    var miss = function () {
      if (++misses >= POLL_MAX_MISSES) deps.stopped("lost");
      else deps.schedule(tick, pollDelay(misses));
    };
    var tick = function () {
      if (!live()) return;
      if (deps.hidden()) { deps.onVisible(tick); return; }
      deps.get(id).then(function (res) {
        if (!live()) return;
        if (res.ok && res.j && res.j.items) {
          misses = 0;
          deps.render(res.j);
          if (res.j.status !== "done") deps.schedule(tick, POLL_MS);
        } else if (res.status === 401) deps.stopped("session");
        else if (res.status === 404) deps.stopped("lost");
        else miss();
      }, function () { if (live()) miss(); });
    };
    deps.schedule(tick, POLL_MS);
  }

  /** Unique, trimmed, non-empty lines (what the service counts against the 200-link cap). */
  function uniqueLinks(text) {
    var seen = {}, out = [];
    String(text).split(/\r\n|\r|\n/).forEach(function (l) {
      var t = l.trim();
      if (t && !Object.prototype.hasOwnProperty.call(seen, t)) { seen[t] = 1; out.push(t); }
    });
    return out;
  }

  // Published so the test suite can check these pure helpers (no DOM needed).
  globalThis.OrbitTunerChordPro = { parse: parse, importBatches: importBatches };
  globalThis.OrbitTunerPortal = { pollDelay: pollDelay, pollJob: pollJob, uniqueLinks: uniqueLinks, POLL_MAX_MISSES: POLL_MAX_MISSES };
  if (typeof document === "undefined") return;

  var csrfMeta = document.querySelector('meta[name="csrf"]');
  var CSRF = csrfMeta ? csrfMeta.getAttribute("content") : "";

  // On narrow screens the nav is a horizontal strip: keep the current page's tab in view.
  var nav = $(".nav"), cur = $('.nav [aria-current="page"]');
  if (nav && cur && nav.scrollWidth > nav.clientWidth) {
    var nr = nav.getBoundingClientRect(), cr = cur.getBoundingClientRect();
    nav.scrollLeft += cr.left - nr.left - (nr.width - cr.width) / 2;
  }

  function lineNode(line) {
    if (line.kind === "comment") return el("p", "cmt", line.text);
    if (line.kind === "tab") return el("div", "tab", line.text || NBSP);
    if (line.kind === "directive") return null;
    var hasChord = line.segments.some(function (s) { return s.chord; });
    if (!hasChord) return el("div", "ln plain", line.segments.map(function (s) { return s.text; }).join(""));
    var ln = el("div", "ln");
    line.segments.forEach(function (s) {
      var words = s.text.match(/\S+\s*|\s+/g) || [""];
      words.forEach(function (w, i) {
        var sg = el("span", "sg");
        sg.appendChild(el("span", "c", i === 0 && s.chord ? s.chord : NBSP));
        sg.appendChild(el("span", "l", w || NBSP));
        ln.appendChild(sg);
      });
    });
    return ln;
  }

  function renderSheet(target, src) {
    var doc = parse(src);
    var frag = document.createDocumentFragment();
    var head = el("header", "sheet-head");
    head.appendChild(el("h3", null, doc.meta.title || "Untitled"));
    if (doc.meta.artist) head.appendChild(el("p", null, doc.meta.artist));
    var facts = [["Key", doc.meta.key], ["Capo", doc.meta.capo], ["Tempo", doc.meta.tempo], ["Time", doc.meta.time]].filter(function (f) { return f[1]; });
    if (facts.length) {
      var fm = el("div", "sheet-meta");
      facts.forEach(function (f) {
        var s = el("span", null, f[0]);
        s.appendChild(el("b", null, f[1]));
        fm.appendChild(s);
      });
      head.appendChild(fm);
    }
    frag.appendChild(head);
    if (!doc.sections.length) frag.appendChild(el("p", "sheet-empty", "Lyrics and chords appear here as you type."));
    doc.sections.forEach(function (sec) {
      var s = el("section", sec.type === "chorus" ? "sec chorus" : "sec");
      var label = sec.label || LABELS[sec.type];
      if (label) s.appendChild(el("div", "lab", label));
      sec.lines.forEach(function (l) {
        var n = lineNode(l);
        if (n) s.appendChild(n);
      });
      frag.appendChild(s);
    });
    target.replaceChildren(frag);
    return doc;
  }

  // ---------------------------------------------------------------------------
  // Editor: live preview, dirty marker, Cmd/Ctrl+S, paste-plain-text dialog.
  // ---------------------------------------------------------------------------
  var editor = $("[data-editor]");
  if (editor) {
    var source = $("[data-source]", editor);
    var preview = $("[data-preview]", editor);
    var original = source.value;
    var queued = false;
    var submitting = false;

    var refresh = function () {
      queued = false;
      renderSheet(preview, source.value);
      document.body.classList.toggle("is-dirty", source.value !== original);
    };
    var schedule = function () {
      if (!queued) { queued = true; requestAnimationFrame(refresh); }
    };
    source.addEventListener("input", schedule);
    $$("select, input", editor).forEach(function (i) {
      i.addEventListener("change", function () { document.body.classList.add("is-dirty"); });
    });

    var isMac = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
    $$("[data-mod]").forEach(function (k) { k.textContent = isMac ? "⌘" : "Ctrl"; });
    document.addEventListener("keydown", function (e) {
      if ((e.metaKey || e.ctrlKey) && (e.key === "s" || e.key === "S")) {
        e.preventDefault();
        if (editor.requestSubmit) editor.requestSubmit(); else editor.submit();
      }
    });
    editor.addEventListener("submit", function () { submitting = true; });
    window.addEventListener("beforeunload", function (e) {
      if (!submitting && document.body.classList.contains("is-dirty")) { e.preventDefault(); e.returnValue = ""; }
    });

    var dialog = $("#paste");
    var opener = $('[data-open="paste"]');
    if (dialog && opener && dialog.showModal) {
      var cform = $("[data-convert]", dialog);
      var errBox = $("[data-error]", dialog);
      opener.addEventListener("click", function () {
        errBox.textContent = "";
        dialog.showModal();
        $("textarea", dialog).focus();
      });
      $("[data-close]", dialog).addEventListener("click", function () { dialog.close(); });
      cform.addEventListener("submit", function (e) {
        e.preventDefault();
        var btn = $('button[type="submit"]', cform);
        btn.disabled = true;
        errBox.textContent = "";
        var body = { text: cform.elements.text.value };
        if (cform.elements.title.value.trim()) body.title = cform.elements.title.value.trim();
        if (cform.elements.artist.value.trim()) body.artist = cform.elements.artist.value.trim();
        fetch("/v1/convert", {
          method: "POST",
          credentials: "same-origin",
          headers: { "content-type": "application/json", "x-csrf": CSRF },
          body: JSON.stringify(body),
        })
          .then(function (r) {
            return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, j: j }; });
          })
          .then(function (res) {
            if (!res.ok || !res.j.chordpro) {
              errBox.textContent = res.status === 413 ? "That text is too long to convert." :
                res.status === 401 ? "Your session expired. Sign in again." : "Could not convert that text.";
              return;
            }
            source.value = res.j.chordpro;
            refresh();
            dialog.close();
            cform.reset();
            source.focus();
          })
          .catch(function () { errBox.textContent = "Network error. Try again."; })
          .then(function () { btn.disabled = false; });
      });
    }
  }

  // ---------------------------------------------------------------------------
  // JSON helper for same-origin calls made with the owner session + X-CSRF.
  // ---------------------------------------------------------------------------
  function api(method, url, body) {
    var init = { method: method, credentials: "same-origin", headers: { accept: "application/json", "x-csrf": CSRF } };
    if (body !== undefined) {
      init.headers["content-type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    return fetch(url, init).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) { return { ok: r.ok, status: r.status, j: j || {} }; });
    });
  }

  // ---------------------------------------------------------------------------
  // Editor: "Convert with AI" rewrites the editor text through /v1/convert (ai: on).
  // ---------------------------------------------------------------------------
  var aiBtn = $("[data-ai-convert]");
  var aiOut = $("[data-ai-out]");
  if (editor && aiBtn && aiOut) {
    var aiSource = $("[data-source]", editor);
    var aiLabel = $("[data-label]", aiBtn);
    var AI_ERRORS = {
      ai_limit_reached: "Today's AI limit is used up.",
      discover_limit_reached: "Today's AI limit is used up.",
      ai_unavailable: "No AI gateway answered. Check AI & keys.",
      no_chords: "No chords found to convert.",
      empty_text: "There is no text to convert.",
      ai_unparseable: "The AI answer could not be used. Try again.",
      too_large: "That text is too long to convert.",
    };
    var aiMsg = function (text, cls) {
      aiOut.replaceChildren(el("span", "chip " + (cls || "missing"), text));
    };
    aiBtn.addEventListener("click", function () {
      var text = aiSource.value.trim();
      if (!text) { aiMsg("Paste some text first."); return; }
      aiBtn.disabled = true;
      aiLabel.textContent = "Converting…";
      aiOut.replaceChildren();
      api("POST", "/v1/convert", { text: text, ai: "on" })
        .then(function (res) {
          if (!res.ok || !res.j.chordpro) {
            aiMsg(res.status === 401 ? "Session expired. Sign in again." : AI_ERRORS[res.j.error] || "Could not convert that text.", "warn");
            return;
          }
          var before = aiSource.value;
          aiSource.value = res.j.chordpro;
          aiSource.dispatchEvent(new Event("input"));
          var conf = typeof res.j.confidence === "number" ? res.j.confidence : null;
          var chip = el("span", "chip " + (res.j.via !== "ai" ? "env" : conf !== null && conf < 0.8 ? "warn" : "portal"));
          chip.appendChild(document.createTextNode(res.j.via === "ai" ? "AI" : "Plain"));
          if (conf !== null) chip.appendChild(el("b", null, Math.round(conf * 100) + "% sure"));
          if (res.j.provider) chip.appendChild(el("code", null, res.j.provider));
          if (conf !== null && conf < 0.8) chip.title = "Low confidence: check the chord placement before saving.";
          var undo = el("button", "link-btn", "Undo");
          undo.type = "button";
          undo.addEventListener("click", function () {
            aiSource.value = before;
            aiSource.dispatchEvent(new Event("input"));
            aiOut.replaceChildren();
            aiSource.focus();
          });
          aiOut.replaceChildren(chip, undo);
        })
        .catch(function () { aiMsg("Network error. Try again.", "warn"); })
        .then(function () {
          aiBtn.disabled = false;
          aiLabel.textContent = "Convert with AI";
        });
    });
  }

  // ---------------------------------------------------------------------------
  // AI & keys: gateway tests and live enabled state.
  // ---------------------------------------------------------------------------
  var TEST_ERRORS = {
    no_key: "No key",
    not_configured: "Needs a model and URL",
    unknown_provider: "Save first",
    network_or_timeout: "Timed out",
    bad_response: "Odd reply",
    empty: "Empty reply",
    too_large: "Reply too large",
  };
  $$("[data-test]").forEach(function (b) {
    var id = b.getAttribute("data-test");
    var out = $('[data-test-out="' + id + '"]');
    b.addEventListener("click", function () {
      b.disabled = true;
      out.className = "gw-test busy";
      out.textContent = "Testing…";
      api("POST", "/portal/ai/test/" + encodeURIComponent(id))
        .then(function (res) {
          var j = res.j;
          out.replaceChildren();
          if (j.ok) {
            out.className = "gw-test ok";
            out.appendChild(el("b", null, j.ms + " ms"));
            if (j.model) out.appendChild(el("code", null, j.model));
          } else {
            out.className = "gw-test bad";
            var code = j.error || "http_" + res.status;
            var m = /^http_(\d+)$/.exec(code);
            out.appendChild(el("b", null, TEST_ERRORS[code] ||
              (m ? (m[1] === "401" || m[1] === "403" ? "Key rejected (" + m[1] + ")" : "HTTP " + m[1]) : code)));
            if (j.ms) out.appendChild(el("code", null, j.ms + " ms"));
          }
        })
        .catch(function () { out.className = "gw-test bad"; out.textContent = "Network error"; })
        .then(function () { b.disabled = false; });
    });
  });
  $$(".gw .switch input").forEach(function (cb) {
    cb.addEventListener("change", function () {
      var row = cb.closest(".gw");
      if (row) row.classList.toggle("off", !cb.checked);
    });
  });

  // ---------------------------------------------------------------------------
  // Import from links: create a job, then poll it every 2 s.
  // ---------------------------------------------------------------------------
  var ITEM_LABEL = {
    queued: "Waiting", done: "Added", duplicate: "Already in library", review: "Needs review",
    failed: "Failed", limit_reached: "Daily limit reached",
  };
  function renderJob(box, job) {
    var pct = job.total ? Math.round((job.done / job.total) * 100) : 100;
    var wrap = el("div", "job");
    wrap.setAttribute("data-job", job.id);
    wrap.setAttribute("data-job-status", job.status);
    var head = el("div", "job-head");
    head.appendChild(el("b", null, job.done + " of " + job.total + " done"));
    head.appendChild(el("span", "status " + (job.status === "done" ? "published" : "review"), job.status === "done" ? "Finished" : "Working"));
    wrap.appendChild(head);
    var bar = el("progress");
    bar.max = 100;
    bar.value = pct;
    bar.textContent = pct + "%";
    wrap.appendChild(bar);
    var ol = el("ol", "job-items");
    job.items.forEach(function (it) {
      var li = el("li", "job-item " + it.status);
      var u = el("span", "job-url", it.url);
      u.title = it.url;
      li.appendChild(u);
      var st = el("span", "job-st", ITEM_LABEL[it.status] || it.status);
      if (it.error && it.status !== "done") st.appendChild(el("code", null, it.error));
      li.appendChild(st);
      if (it.songId) {
        var a = el("a", "btn sm quiet", "Open");
        a.href = "/portal/songs/" + encodeURIComponent(it.songId);
        li.appendChild(a);
      } else li.appendChild(el("span"));
      ol.appendChild(li);
    });
    wrap.appendChild(ol);
    box.replaceChildren(wrap);
  }
  var STOPPED = {
    lost: "Lost contact with the import — reload to check progress.",
    session: "Session expired — sign in again.",
  };
  function watchJob(box, id) {
    pollJob(id, {
      get: function (jid) { return api("GET", "/v1/import/jobs/" + encodeURIComponent(jid)); },
      schedule: function (fn, ms) { setTimeout(fn, ms); },
      hidden: function () { return document.hidden; },
      onVisible: function (fn) {
        var h = function () {
          if (document.hidden) return;
          document.removeEventListener("visibilitychange", h);
          fn();
        };
        document.addEventListener("visibilitychange", h);
      },
      render: function (job) { renderJob(box, job); },
      stopped: function (reason) {
        var p = el("p", "err job-stopped", STOPPED[reason] || STOPPED.lost);
        p.setAttribute("role", "alert");
        var prev = $(".job-stopped", box);
        if (prev) prev.remove();
        box.appendChild(p);
      },
    });
  }
  var links = $("[data-links]");
  if (links) {
    var linksOut = $("[data-links-out]");
    var linksGo = $("[data-links-go]", links);
    var linksCount = $("[data-links-count]", links);
    var linksArea = links.elements.urls;
    var linesOf = function () { return uniqueLinks(linksArea.value); };
    var countLinks = function () {
      var n = linesOf().length;
      linksCount.textContent = n ? n + (n === 1 ? " link" : " links") + (n > 200 ? " (200 at most)" : "") : "";
      linksCount.classList.toggle("over", n > 200);
    };
    linksArea.addEventListener("input", countLinks);
    countLinks();
    var LINK_ERRORS = {
      invalid_input: "Paste at most 200 links, one per line.",
      discover_limit_reached: "That is more links than today's limit allows.",
      queue_unavailable: "The import queue is unavailable right now. Try again in a minute.",
    };
    links.addEventListener("submit", function (e) {
      if (!window.fetch) return;
      e.preventDefault();
      var urls = linesOf();
      if (!urls.length) return;
      if (urls.length > 200) {
        linksOut.replaceChildren(el("p", "err", "That is " + urls.length + " different links. Import at most 200 at a time."));
        linksArea.focus();
        return;
      }
      linksGo.disabled = true;
      api("POST", "/v1/import/links", { urls: urls })
        .then(function (res) {
          if (res.status === 202 && res.j.jobId) {
            linksArea.value = "";
            countLinks();
            renderJob(linksOut, { id: res.j.jobId, status: "queued", total: urls.length, done: 0, items: urls.map(function (u) { return { url: u, status: "queued" }; }) });
            if (history.replaceState) history.replaceState(null, "", "/portal/transfer/jobs/" + encodeURIComponent(res.j.jobId));
            watchJob(linksOut, res.j.jobId);
            return;
          }
          var p = el("p", "err", res.status === 401 ? "Your session expired. Sign in again." : LINK_ERRORS[res.j.error] || "Could not start the import (" + (res.j.error || res.status) + ").");
          linksOut.replaceChildren(p);
        })
        .catch(function () { linksOut.replaceChildren(el("p", "err", "Network error. Try again.")); })
        .then(function () { linksGo.disabled = false; });
    });
    var existing = $("[data-job]", linksOut);
    if (existing && existing.getAttribute("data-job-status") !== "done") watchJob(linksOut, existing.getAttribute("data-job"));
  }

  // ---------------------------------------------------------------------------
  // Library filters submit on change.
  // ---------------------------------------------------------------------------
  $$("[data-autosubmit]").forEach(function (s) {
    s.addEventListener("change", function () { s.form.submit(); });
  });

  // ---------------------------------------------------------------------------
  // Users: picking a Plus end date selects Plus.
  // ---------------------------------------------------------------------------
  $$("[data-plan-date]").forEach(function (d) {
    d.addEventListener("change", function () {
      var plus = d.form && d.form.querySelector('input[name="plan"][value="plus"]');
      if (d.value && plus) plus.checked = true;
    });
  });

  // ---------------------------------------------------------------------------
  // Destructive actions ask first.
  // ---------------------------------------------------------------------------
  $$("form[data-confirm]").forEach(function (f) {
    f.addEventListener("submit", function (e) {
      if (!window.confirm(f.getAttribute("data-confirm"))) e.preventDefault();
    });
  });

  // ---------------------------------------------------------------------------
  // Pairing: countdown and copy.
  // ---------------------------------------------------------------------------
  var timer = $("[data-expires]");
  if (timer) {
    var expires = Number(timer.getAttribute("data-expires"));
    var ttl = Number(timer.getAttribute("data-ttl")) || 600000;
    var start = Date.now();
    var startLeft = expires - start;
    var bar = $(".bar i", timer);
    var left = $("[data-left]", timer);
    var tick = function () {
      // Count from the server's remaining time so a skewed device clock does not matter.
      var ms = Math.max(0, startLeft - (Date.now() - start));
      var s = Math.ceil(ms / 1000);
      left.textContent = Math.floor(s / 60) + ":" + String(s % 60).padStart(2, "0");
      bar.style.transform = "scaleX(" + Math.max(0, Math.min(1, ms / ttl)) + ")";
      if (ms <= 0) {
        timer.classList.add("expired");
        timer.querySelector("p").textContent = "Expired. Generate a new code.";
        var qr = $(".qr");
        if (qr) qr.classList.add("stale");
        return;
      }
      setTimeout(tick, 250);
    };
    tick();
  }
  $$("[data-copy]").forEach(function (b) {
    b.addEventListener("click", function () {
      var input = $(b.getAttribute("data-copy"));
      if (!input) return;
      var done = function () {
        b.textContent = "Copied";
        setTimeout(function () { b.textContent = "Copy"; }, 1600);
      };
      if (navigator.clipboard) navigator.clipboard.writeText(input.value).then(done, function () { input.select(); });
      else { input.select(); document.execCommand("copy"); done(); }
    });
  });

  // ---------------------------------------------------------------------------
  // Import: drag and drop, then upload in chunks through /v1/import.
  // The service caps one request at import_max_songs; loose files go in batches of at most
  // 150 files / 16 MB, and each ZIP travels alone (an archive cannot be split client-side).
  // ---------------------------------------------------------------------------
  var imp = $("[data-import]");
  if (imp) {
    var input = $("[data-files]", imp);
    var drop = $("[data-drop]", imp);
    var picked = $("[data-picked]", imp);
    var out = $("[data-result]");
    var go = $("[data-go]", imp);
    var prog = $(".progress i", imp);
    var MAX = Number(imp.getAttribute("data-max")) || 200;
    var BATCH_FILES = Math.min(150, MAX);
    var BATCH_BYTES = 16 * 1024 * 1024;

    var fmtBytes = function (n) {
      return n < 1024 ? n + " B" : n < 1048576 ? Math.round(n / 1024) + " KB" : (n / 1048576).toFixed(1) + " MB";
    };
    var describe = function () {
      var fs = Array.prototype.slice.call(input.files || []);
      if (!fs.length) { picked.textContent = ""; return; }
      var bytes = fs.reduce(function (a, f) { return a + f.size; }, 0);
      var zips = fs.filter(function (f) { return /\.zip$/i.test(f.name); }).length;
      picked.textContent = fs.length + (fs.length === 1 ? " file" : " files") + " · " + fmtBytes(bytes) +
        (zips ? " · " + zips + (zips === 1 ? " archive" : " archives") : "");
    };
    input.addEventListener("change", describe);
    ["dragenter", "dragover"].forEach(function (t) {
      drop.addEventListener(t, function (e) { e.preventDefault(); drop.classList.add("over"); });
    });
    ["dragleave", "drop"].forEach(function (t) {
      drop.addEventListener(t, function () { drop.classList.remove("over"); });
    });
    drop.addEventListener("drop", function (e) {
      e.preventDefault();
      if (e.dataTransfer && e.dataTransfer.files.length) {
        input.files = e.dataTransfer.files;
        describe();
      }
    });

    var batches = function (files) { return importBatches(files, BATCH_FILES, BATCH_BYTES); };

    var showSummary = function (total, errors) {
      var box = el("div", "summary");
      box.setAttribute("role", "status");
      var nums = el("div", "nums");
      var stat = function (n, label, cls) {
        var d = el("div", cls || null);
        d.appendChild(el("b", null, String(n)));
        d.appendChild(document.createTextNode(" " + label));
        nums.appendChild(d);
      };
      stat(total.added, "added", "ok");
      stat(total.duplicates.length, "already in the library");
      stat(total.failed.length, "could not be read", total.failed.length ? "bad" : "");
      box.appendChild(nums);
      errors.forEach(function (m) {
        var p = el("p", "err", m);
        box.appendChild(p);
      });
      var list = function (title, items, open, withCode) {
        if (!items.length) return;
        var d = el("details");
        d.open = open;
        d.appendChild(el("summary", null, title));
        var ul = el("ul");
        items.slice(0, 200).forEach(function (it) {
          var li = el("li");
          li.appendChild(el("span", null, withCode ? it.name : it));
          if (withCode) li.appendChild(el("code", null, it.error));
          ul.appendChild(li);
        });
        d.appendChild(ul);
        box.appendChild(d);
      };
      list("Files that failed", total.failed, total.failed.length <= 5, true);
      list("Skipped as duplicates", total.duplicates, false, false);
      out.replaceChildren(box);
    };

    imp.addEventListener("submit", function (e) {
      var files = Array.prototype.slice.call(input.files || []);
      if (!files.length || !window.fetch) return; // let the browser post the form
      e.preventDefault();
      var groups = batches(files);
      var total = { added: 0, duplicates: [], failed: [] };
      var errors = [];
      var i = 0;
      imp.classList.add("busy");
      go.disabled = true;
      go.textContent = "Importing…";
      prog.style.width = "0%";

      var next = function () {
        if (i >= groups.length) {
          imp.classList.remove("busy");
          go.disabled = false;
          go.textContent = "Import";
          showSummary(total, errors);
          if (total.added) { input.value = ""; describe(); }
          return;
        }
        var group = groups[i++];
        var fd = new FormData();
        group.forEach(function (f) { fd.append("files", f, f.name); });
        var aiSel = $("[data-ai-mode]", imp);
        if (aiSel) fd.append("ai", aiSel.value);
        fetch("/v1/import", { method: "POST", credentials: "same-origin", headers: { "x-csrf": CSRF }, body: fd })
          .then(function (r) {
            return r.json().catch(function () { return {}; }).then(function (j) { return { status: r.status, j: j }; });
          })
          .then(function (res) {
            var j = res.j || {};
            if (typeof j.added === "number") total.added += j.added;
            if (Array.isArray(j.duplicates)) total.duplicates = total.duplicates.concat(j.duplicates);
            if (Array.isArray(j.failed)) total.failed = total.failed.concat(j.failed);
            var what = group.length === 1 ? group[0].name :
              group.length + " files (" + group.slice(0, 3).map(function (f) { return f.name; }).join(", ") + (group.length > 3 ? ", …" : "") + ")";
            if (j.error === "too_many_songs") {
              errors.push(what + " holds more than " + (j.max || MAX) + " songs, so nothing from it was imported. Split it into archives of at most " + (j.max || MAX) + " songs.");
            } else if (j.error === "too_large" || res.status === 413) {
              errors.push(what + " is too large to import in one go (20 MB at most).");
            } else if (res.status === 401) {
              errors.push("Your session expired. Sign in again and retry.");
              i = groups.length;
            } else if (res.status >= 400) {
              errors.push(what + " could not be imported (" + (j.error || res.status) + ").");
            }
          })
          .catch(function () { errors.push("Network error while uploading. Some files may not have been imported."); })
          .then(function () {
            prog.style.width = Math.round((i / groups.length) * 100) + "%";
            next();
          });
      };
      next();
    });
  }
})();
