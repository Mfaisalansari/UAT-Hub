/* UAT Hub: small progressive enhancements. Every page still works without JavaScript. */
(function () {
    'use strict';

    // Theme: dark, light or auto (follows the computer). Saved per browser.
    var root = document.documentElement;
    var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: light)') : null;
    var applyTheme = function (pref) {
        var light = pref === 'light' || (pref === 'auto' && media && media.matches);
        root.setAttribute('data-theme', light ? 'light' : 'dark');
        root.setAttribute('data-theme-pref', pref);
        document.querySelectorAll('[data-theme-set]').forEach(function (b) {
            b.setAttribute('aria-pressed', b.getAttribute('data-theme-set') === pref ? 'true' : 'false');
        });
    };
    applyTheme(root.getAttribute('data-theme-pref') || 'dark');
    document.querySelectorAll('[data-theme-set]').forEach(function (b) {
        b.addEventListener('click', function () {
            var pref = b.getAttribute('data-theme-set');
            try { localStorage.setItem('uathub-theme', pref); } catch (e) {}
            applyTheme(pref);
        });
    });
    if (media) {
        var follow = function () { if (root.getAttribute('data-theme-pref') === 'auto') applyTheme('auto'); };
        if (media.addEventListener) media.addEventListener('change', follow); else if (media.addListener) media.addListener(follow);
    }

    // Selects and file inputs that submit their form on change.
    document.querySelectorAll('[data-autosubmit]').forEach(function (el) {
        el.addEventListener('change', function () { el.form && el.form.submit(); });
    });

    // Confirm before destructive actions.
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
        form.addEventListener('submit', function (e) {
            if (!window.confirm(form.getAttribute('data-confirm'))) e.preventDefault();
        });
    });
    document.querySelectorAll('button[data-confirm]').forEach(function (b) {
        b.addEventListener('click', function (e) {
            if (!window.confirm(b.getAttribute('data-confirm'))) e.preventDefault();
        });
    });

    // Copy personal links. navigator.clipboard needs HTTPS, so fall back to execCommand on plain HTTP.
    document.querySelectorAll('[data-copy]').forEach(function (btn) {
        btn.addEventListener('click', function () {
            var input = btn.parentElement.querySelector('input');
            var done = function () {
                var old = btn.textContent;
                btn.textContent = 'Copied ✓';
                btn.classList.add('primary');
                setTimeout(function () { btn.textContent = old; btn.classList.remove('primary'); }, 1600);
            };
            if (navigator.clipboard && window.isSecureContext) {
                navigator.clipboard.writeText(input.value).then(done);
            } else {
                input.select();
                document.execCommand('copy');
                input.blur();
                done();
            }
        });
    });

    // Jira push: select all + live count.
    var all = document.querySelector('[data-select-all]');
    if (all) {
        var boxName = all.getAttribute('data-select-all') || 'ids';
        var boxes = Array.prototype.slice.call(document.querySelectorAll('input[name="' + boxName + '"]'));
        var count = document.querySelector('[data-count]');
        var update = function () {
            var n = boxes.filter(function (b) { return b.checked; }).length;
            if (count) count.textContent = n;
            all.checked = n === boxes.length;
            all.indeterminate = n > 0 && n < boxes.length;
        };
        all.addEventListener('change', function () {
            boxes.forEach(function (b) { b.checked = all.checked; });
            update();
        });
        boxes.forEach(function (b) { b.addEventListener('change', update); });
        update();
    }

    // Scenario editor: add and remove step rows.
    var stepList = document.querySelector('[data-steps]');
    var addStep = document.querySelector('[data-add-step]');
    if (stepList && addStep) {
        var wireRemove = function (li) {
            var b = li.querySelector('[data-remove-step]');
            if (b) b.addEventListener('click', function () {
                if (stepList.children.length > 1) li.remove();
                else li.querySelectorAll('textarea').forEach(function (t) { t.value = ''; });
            });
        };
        Array.prototype.forEach.call(stepList.children, wireRemove);
        addStep.addEventListener('click', function () {
            var tpl = stepList.lastElementChild;
            var li = tpl.cloneNode(true);
            li.querySelectorAll('textarea').forEach(function (t) { t.value = ''; t.textContent = ''; });
            stepList.appendChild(li);
            wireRemove(li);
            li.querySelector('textarea').focus();
        });
    }

    // Register bulk bar: show only the extra field the chosen action needs.
    var bulkAction = document.querySelector('[data-bulk-action]');
    if (bulkAction) {
        var syncBulk = function () {
            document.querySelectorAll('[data-bulk-for]').forEach(function (el) {
                var on = el.getAttribute('data-bulk-for') === bulkAction.value;
                el.hidden = !on;
                el.required = on && el.tagName === 'INPUT';
            });
        };
        bulkAction.addEventListener('change', syncBulk);
        syncBulk();
    }

    // Exit report: print, or save as PDF from the print dialog.
    document.querySelectorAll('[data-print]').forEach(function (b) {
        b.addEventListener('click', function () { window.print(); });
    });

    // AI assist: suggest type and severity on the Log feedback form.
    var csrfToken = document.querySelector('meta[name="csrf-token"]');
    var csrfHeader = document.querySelector('meta[name="csrf-header"]');
    var aiBtn = document.querySelector('[data-ai-classify]');
    if (aiBtn) {
        var aiOut = document.querySelector('[data-ai-result]');
        aiBtn.addEventListener('click', function () {
            var form = aiBtn.closest('form');
            var val = function (name) {
                var el = form.querySelector('[name="' + name + '"]:checked') || form.querySelector('[name="' + name + '"]');
                return el && el.type !== 'radio' ? el.value : (el ? el.value : '');
            };
            var body = new URLSearchParams();
            ['title', 'description', 'expected', 'actual', 'module'].forEach(function (n) { body.append(n, val(n)); });
            var lob = form.querySelector('[name="lob"]:checked');
            body.append('lob', lob ? lob.value : '');
            var headers = { 'Content-Type': 'application/x-www-form-urlencoded' };
            if (csrfToken && csrfHeader) headers[csrfHeader.content] = csrfToken.content;
            aiBtn.disabled = true;
            aiOut.textContent = 'Asking AI…';
            fetch('/api/ai/classify', { method: 'POST', credentials: 'same-origin', headers: headers, body: body })
                .then(function (r) { return r.json().then(function (j) { return { ok: r.ok, j: j }; }); })
                .then(function (res) {
                    if (!res.ok) { aiOut.textContent = res.j.error || 'AI suggestion failed.'; return; }
                    var t = form.querySelector('[name="type"][value="' + res.j.type + '"]');
                    var s = form.querySelector('[name="severity"][value="' + res.j.severity + '"]');
                    if (t) t.checked = true;
                    if (s) s.checked = true;
                    aiOut.textContent = 'AI suggests ' + (t ? t.parentNode.textContent.trim() : res.j.type) + ', '
                        + (s ? s.parentNode.textContent.trim() : res.j.severity) + ': ' + res.j.reason + ' Change either if it\'s wrong.';
                })
                .catch(function () { aiOut.textContent = 'AI suggestion failed. Try again.'; })
                .then(function () { aiBtn.disabled = false; });
        });
    }

    // Comments: clicking a name inserts "@Name " at the cursor.
    document.querySelectorAll('[data-mention]').forEach(function (b) {
        b.addEventListener('click', function () {
            var box = b.closest('form').querySelector('[data-mention-box]');
            var tag = '@' + b.getAttribute('data-mention') + ' ';
            var at = box.selectionStart || box.value.length;
            var before = box.value.slice(0, at);
            if (before && !/\s$/.test(before)) tag = ' ' + tag;
            box.value = before + tag + box.value.slice(at);
            box.focus();
            box.selectionStart = box.selectionEnd = at + tag.length;
        });
    });

    // Business review: target release only for decisions that go to Jira.
    var release = document.querySelector('[data-release]');
    if (release) {
        var radios = document.querySelectorAll('input[name="decision"]');
        var sync = function () {
            var picked = document.querySelector('input[name="decision"]:checked');
            release.hidden = !picked || picked.getAttribute('data-jira') !== 'true';
        };
        radios.forEach(function (r) { r.addEventListener('change', sync); });
        sync();
    }

    // Log form: paste screenshots, drop files, preview them.
    var fileInput = document.getElementById('files');
    var zone = document.querySelector('[data-dropzone]');
    var previews = document.querySelector('[data-previews]');
    if (fileInput && zone && previews && window.DataTransfer) {
        var bucket = new DataTransfer();
        var render = function () {
            previews.innerHTML = '';
            Array.prototype.forEach.call(bucket.files, function (file, i) {
                var item = document.createElement('div');
                item.className = 'thumb';
                if (file.type.indexOf('image/') === 0) {
                    var img = document.createElement('img');
                    img.alt = file.name;
                    img.src = URL.createObjectURL(file);
                    item.appendChild(img);
                } else {
                    var ico = document.createElement('span');
                    ico.className = 'file-ico';
                    ico.textContent = file.name.split('.').pop().toUpperCase();
                    item.appendChild(ico);
                }
                var name = document.createElement('small');
                name.textContent = file.name;
                item.appendChild(name);
                var rm = document.createElement('button');
                rm.type = 'button';
                rm.className = 'linkish danger';
                rm.textContent = 'Remove';
                rm.addEventListener('click', function () {
                    var next = new DataTransfer();
                    Array.prototype.forEach.call(bucket.files, function (f, j) { if (j !== i) next.items.add(f); });
                    bucket = next;
                    fileInput.files = bucket.files;
                    render();
                });
                item.appendChild(rm);
                previews.appendChild(item);
            });
        };
        var add = function (files) {
            Array.prototype.forEach.call(files, function (f) {
                if (f.name === 'image.png' || !f.name) {
                    var stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
                    f = new File([f], 'screenshot-' + stamp + '.png', { type: f.type || 'image/png' });
                }
                bucket.items.add(f);
            });
            fileInput.files = bucket.files;
            render();
        };
        fileInput.addEventListener('change', function () {
            var chosen = Array.prototype.slice.call(fileInput.files);
            fileInput.files = bucket.files;
            add(chosen);
        });
        document.addEventListener('paste', function (e) {
            var items = (e.clipboardData && e.clipboardData.files) || [];
            if (items.length) { e.preventDefault(); add(items); }
        });
        ['dragenter', 'dragover'].forEach(function (t) {
            zone.addEventListener(t, function (e) { e.preventDefault(); zone.classList.add('over'); });
        });
        ['dragleave', 'drop'].forEach(function (t) {
            zone.addEventListener(t, function (e) { e.preventDefault(); zone.classList.remove('over'); });
        });
        zone.addEventListener('drop', function (e) { if (e.dataTransfer) add(e.dataTransfer.files); });
    }

    // Log form: look for similar titles while typing.
    var title = document.querySelector('[data-similar]');
    var box = document.querySelector('[data-similar-box]');
    var list = document.querySelector('[data-similar-list]');
    if (title && box && list) {
        var timer;
        title.addEventListener('input', function () {
            clearTimeout(timer);
            var q = title.value.trim();
            if (q.length < 8) { box.hidden = true; return; }
            timer = setTimeout(function () {
                fetch('/api/similar?title=' + encodeURIComponent(q), { credentials: 'same-origin' })
                    .then(function (r) { return r.ok ? r.json() : []; })
                    .then(function (rows) {
                        list.innerHTML = '';
                        rows.forEach(function (row) {
                            var a = document.createElement('a');
                            a.href = '/feedback/' + row.id;
                            a.target = '_blank';
                            var meta = document.createElement('span');
                            meta.className = 'mono';
                            meta.textContent = row.code + ' · ' + row.stage;
                            var t = document.createElement('span');
                            t.textContent = row.title;
                            a.appendChild(meta);
                            a.appendChild(t);
                            list.appendChild(a);
                        });
                        box.hidden = rows.length === 0;
                    })
                    .catch(function () { box.hidden = true; });
            }, 350);
        });
    }
})();
