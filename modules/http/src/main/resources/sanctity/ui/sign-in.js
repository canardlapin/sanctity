import { h, svg, I } from './elements.js';

export function connectView(app, { notice, institutional, demo, signIn, rememberDestination, explain }) {
  let tokenInput, err;
  const submit = async ev => {
    ev.preventDefault();
    const token = tokenInput.value.trim();
    err.replaceChildren();
    if (!demo && !token) { err.append(h('p', { class: 'notice err', role: 'alert' }, 'Enter an access token.')); tokenInput.setAttribute('aria-invalid', 'true'); tokenInput.focus(); return; }
    try {
      await signIn(token);
    } catch (e) {
      err.append(h('p', { class: 'notice err', role: 'alert' }, explain(e)));
      if (e.status === 401) { tokenInput.setAttribute('aria-invalid', 'true'); tokenInput.focus(); }
    }
  };
  const clearInvalid = ev => ev.target.removeAttribute('aria-invalid');
  const rings = svg(['M260 20a240 240 0 1 0 0 480a240 240 0 1 0 0-480', 'M260 90a170 170 0 1 0 0 340a170 170 0 1 0 0-340', 'M260 160a100 100 0 1 0 0 200a100 100 0 1 0 0-200'], 'rings', '0 0 520 520');
  app.replaceChildren(h('div', { class: 'connect' },
    h('section', { class: 'connect-hero', 'aria-label': 'About Sanctity' },
      h('div', { class: 'brand' }, I.mark(), h('span', {}, 'Sanctity')),
      h('div', { class: 'hero-copy' },
        h('p', { class: 'hero-title' }, 'Exact scientific states, explained.'),
        h('p', { class: 'hero-lead' }, 'A BIDS-native repository. Every release fixes its content, interpretation and admission evidence. Every change is previewed before it is accepted.'),
        h('dl', { class: 'laws' },
          h('div', {}, h('dt', {}, 'Fidelity'), h('dd', {}, 'Exports reproduce the retained tree byte for byte.')),
          h('div', {}, h('dt', {}, 'Explanation'), h('dd', {}, 'Every metadata value names the file it came from.')),
          h('div', {}, h('dt', {}, 'Authority'), h('dd', {}, 'Access is granted explicitly and can be withdrawn.')))),
      rings, h('p', { class: 'hero-foot' }, 'Console for the Sanctity v1 API')),
    h('main', { class: 'connect-form', id: 'main', tabindex: '-1' },
      h('form', { class: 'stack s18', onsubmit: submit, novalidate: true },
        h('div', { class: 'stack s6' },
          h('h1', { class: 'h2' }, 'Sign in'),
          h('p', { class: 'muted small' }, institutional ? 'Use your institutional account or an operator-issued access token.' : 'Use a principal credential issued by your repository operator. It stays in this browser tab only.')),
        institutional ? h('a', { class: 'btn primary block', href: '/auth/login',
          onclick: rememberDestination }, 'Sign in with your institution') : null,
        notice ? h('p', { class: 'notice warn', role: 'status' }, notice) : null,
        h('div', { class: 'field' }, h('label', { for: 'token' }, 'Access token'),
          tokenInput = h('input', { class: 'input mono', id: 'token', type: 'password', oninput: clearInvalid, 'aria-describedby': 'connect-errors', autocomplete: 'off', spellcheck: 'false', placeholder: demo ? 'Demo mode: any value works' : 'Paste your token' })),
        err = h('div', { class: 'stack', id: 'connect-errors' }),
        h('button', { class: 'btn primary block', type: 'submit' }, 'Sign in')))));
  if (institutional) app.querySelector('a[href="/auth/login"]').focus(); else tokenInput.focus();
}
