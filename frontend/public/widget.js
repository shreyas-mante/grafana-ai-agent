/*! PulseAgent widget. Usage:
 * <script src="https://YOUR-PULSEAGENT/widget.js" data-key="pa_widget_..." data-service="payment-service" async></script>
 * The widget key only works from the origins you allowed when creating it.
 */
(function () {
  'use strict';
  var script = document.currentScript;
  if (!script || window.__pulseAgentWidget) return;
  window.__pulseAgentWidget = true;
  var key = script.getAttribute('data-key') || '';
  var service = (script.getAttribute('data-service') || '').replace(/[^A-Za-z0-9._-]/g, '').slice(0, 100);
  var heading = (script.getAttribute('data-title') || 'Ask about your system').slice(0, 60);
  var base;
  try { base = new URL(script.src).origin; } catch (e) { return; }
  if (!/^pa_widget_/.test(key)) { return; }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function mount() {
    var host = document.createElement('div');
    host.style.cssText = 'position:fixed;right:20px;bottom:20px;z-index:2147483000;';
    document.body.appendChild(host);
    var root = host.attachShadow({ mode: 'closed' });
    var style = document.createElement('style');
    style.textContent = [
      ':host{all:initial}',
      '.wrap{display:flex;flex-direction:column;align-items:flex-end}',
      '*{box-sizing:border-box;font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}',
      '.fab{width:52px;height:52px;border-radius:50%;border:none;background:#17191c;color:#fff;font-size:22px;cursor:pointer;box-shadow:0 4px 14px rgba(0,0,0,.25)}',
      '.panel{display:none;width:360px;max-width:calc(100vw - 40px);height:480px;max-height:calc(100vh - 100px);background:#fff;color:#17191c;border:1px solid #e3e6eb;border-radius:14px;box-shadow:0 8px 30px rgba(0,0,0,.18);margin-bottom:10px;flex-direction:column;overflow:hidden}',
      '.open .panel{display:flex}',
      '.head{padding:12px 14px;border-bottom:1px solid #e3e6eb;font-weight:600;display:flex;justify-content:space-between;align-items:center}',
      '.x{border:none;background:none;font-size:18px;cursor:pointer;color:#69707a}',
      '.msgs{flex:1;overflow-y:auto;padding:12px;display:flex;flex-direction:column;gap:8px}',
      '.m{padding:8px 11px;border-radius:10px;max-width:92%;white-space:pre-wrap;word-wrap:break-word}',
      '.u{align-self:flex-end;background:#eaeeff}',
      '.a{align-self:flex-start;background:#f1f3f6}',
      '.muted{color:#69707a;font-size:12.5px}',
      'form{display:flex;gap:8px;padding:10px;border-top:1px solid #e3e6eb}',
      'input{flex:1;border:1px solid #e3e6eb;border-radius:8px;padding:8px 10px;color:#17191c;background:#fff}',
      'button.send{border:none;border-radius:8px;padding:8px 12px;background:#17191c;color:#fff;cursor:pointer}',
      'button.send:disabled{opacity:.5}',
      '.err{color:#c2362f;font-size:12.5px;padding:0 12px 6px}',
      '.foot{font-size:11px;color:#69707a;text-align:center;padding:0 0 6px}'
    ].join('');
    root.appendChild(style);

    var wrap = el('div', 'wrap');
    var panel = el('div', 'panel');
    var head = el('div', 'head');
    head.appendChild(el('span', '', heading));
    var close = el('button', 'x', '×');
    close.setAttribute('aria-label', 'Close');
    head.appendChild(close);
    var msgs = el('div', 'msgs');
    msgs.appendChild(el('div', 'm a muted', 'Ask about errors, latency or anything odd. I investigate your metrics, code and runbooks. This takes about 30 seconds.'));
    var errBox = el('div', 'err');
    var form = el('form', '');
    var input = el('input', '');
    input.placeholder = 'Why is checkout slow?';
    input.maxLength = 500;
    var send = el('button', 'send', 'Ask');
    send.type = 'submit';
    form.appendChild(input);
    form.appendChild(send);
    panel.appendChild(head);
    panel.appendChild(msgs);
    panel.appendChild(errBox);
    panel.appendChild(form);
    panel.appendChild(el('div', 'foot', 'Powered by PulseAgent'));
    var fab = el('button', 'fab', '◉');
    fab.setAttribute('aria-label', 'Open PulseAgent');
    wrap.appendChild(panel);
    wrap.appendChild(fab);
    root.appendChild(wrap);

    fab.addEventListener('click', function () { wrap.classList.toggle('open'); if (wrap.classList.contains('open')) input.focus(); });
    close.addEventListener('click', function () { wrap.classList.remove('open'); });
    input.addEventListener('input', function () { errBox.textContent = ''; });

    function add(cls, text) {
      var m = el('div', 'm ' + cls, text);
      msgs.appendChild(m);
      msgs.scrollTop = msgs.scrollHeight;
      return m;
    }

    function call(method, path, body) {
      return fetch(base + path, {
        method: method,
        mode: 'cors',
        credentials: 'omit',
        headers: { 'Content-Type': 'application/json', 'X-Widget-Key': key },
        body: body ? JSON.stringify(body) : undefined
      }).then(function (r) {
        return r.json().catch(function () { return {}; }).then(function (d) {
          if (!r.ok) throw new Error(r.status === 401 ? 'This widget isn’t allowed on this site.' : (d && d.error) || 'Request failed');
          return d;
        });
      });
    }

    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var q = input.value.trim();
      if (!q) { errBox.textContent = 'Type a question first.'; return; }
      input.value = '';
      send.disabled = true;
      add('u', q);
      var pending = add('a muted', 'Investigating…');
      call('POST', '/api/widget/investigations', { question: q, service: service || null }).then(function (d) {
        var started = Date.now();
        (function poll() {
          call('GET', '/api/widget/investigations/' + encodeURIComponent(d.id)).then(function (s) {
            if (s.status === 'COMPLETED') {
              pending.className = 'm a';
              var text = (s.title ? s.title + '\n\n' : '') + (s.likelyCause ? 'Likely cause: ' + s.likelyCause + '\n' : '') +
                (s.confidence ? 'Confidence: ' + s.confidence + '\n' : '') + (s.summary ? '\n' + s.summary : '');
              if (s.nextSteps && s.nextSteps.length) text += '\n\nNext steps:\n- ' + s.nextSteps.join('\n- ');
              pending.textContent = text;
              send.disabled = false;
            } else if (s.status === 'FAILED' || Date.now() - started > 180000) {
              pending.textContent = s.failure || 'That took too long. Try again.';
              send.disabled = false;
            } else {
              pending.textContent = 'Investigating… (' + (s.steps || 0) + ' checks so far)';
              setTimeout(poll, 2000);
            }
          }).catch(function (err) { pending.textContent = err.message; send.disabled = false; });
        })();
      }).catch(function (err) { pending.textContent = err.message; send.disabled = false; });
    });
  }

  if (document.body) mount(); else document.addEventListener('DOMContentLoaded', mount);
})();
