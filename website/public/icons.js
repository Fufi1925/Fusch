/* Fusch's decorative icons are monochrome SVGs, never platform-color emoji. */
(function () {
  'use strict';
  const shapes = Object.freeze({
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    pin: '<path d="M12 22s7-7.8 7-13a7 7 0 0 0-14 0c0 5.2 7 13 7 13Z"/><circle cx="12" cy="9" r="2.5"/>',
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 8-3 9h18c0-1-3-2-3-9ZM10 21h4"/>',
    settings: '<circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 0 0-.1-1l2-1.6-2-3.5-2.4 1a7 7 0 0 0-1.7-1L14.4 3h-4.8l-.4 2.9a7 7 0 0 0-1.7 1l-2.4-1-2 3.5 2 1.6a7 7 0 0 0 0 2l-2 1.6 2 3.5 2.4-1a7 7 0 0 0 1.7 1l.4 2.9h4.8l.4-2.9a7 7 0 0 0 1.7-1l2.4 1 2-3.5-2-1.6c.1-.3.1-.7.1-1Z"/>',
    battery: '<rect x="2" y="7" width="18" height="10" rx="2"/><path d="M22 10v4M11 9l-2 4h3l-1 3"/>',
    warning: '<path d="m12 3 10 18H2L12 3Z"/><path d="M12 9v5M12 17h.01"/>',
    walk: '<circle cx="13" cy="4.5" r="1.7"/><path d="m11 9-2 4 3 2-1 6m1-6 4 6m-5-12 4 2 2-1m-6-1-3 2-2 3"/>',
    bike: '<circle cx="5" cy="18" r="3"/><circle cx="19" cy="18" r="3"/><path d="m5 18 4-9 4 9H5m8 0 5-9 1 9m-11-9h4m-4-2h3"/>',
    car: '<path d="M4 17h16l-1-7-2-3H7l-2 3-1 7ZM4 12h16M7 17v2m10-2v2M7 14h2m6 0h2"/>',
    loop: '<path d="M18 7H6a4 4 0 0 0-4 4m0-4v4h4m0 6h12a4 4 0 0 0 4-4m0 4v-4h-4"/>',
    satellite: '<path d="m9 9 6 6-3 3-6-6 3-3Zm8-3 3-3 2 2-3 3m-14 9-3 3 2 2 3-3M13 8l3-3M8 13l-3 3"/>',
    route: '<circle cx="5" cy="18" r="2"/><circle cx="19" cy="5" r="2"/><path d="M7 18h7a4 4 0 0 0 0-8h-3a3 3 0 0 1 0-6h6"/>',
    map: '<path d="m2 5 6-2 8 2 6-2v16l-6 2-8-2-6 2V5ZM8 3v16M16 5v16"/>',
    moon: '<path d="M20 15.5A8 8 0 0 1 8.5 4 8 8 0 1 0 20 15.5Z"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5M17.5 17.5 19 19M19 5l-1.5 1.5M6.5 17.5 5 19"/>',
    reload: '<path d="M20 7v5h-5M4 17v-5h5M5 9a8 8 0 0 1 14-2l1 5M4 12l1 5a8 8 0 0 0 14-2"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3m-4 4v3"/>',
    key: '<circle cx="8" cy="9" r="4"/><path d="m11 12 9 9m-5-5 2-2m0 5 2-2"/>',
    check: '<path d="m4 12 5 5L20 6"/>',
    cross: '<path d="M5 5 19 19M19 5 5 19"/>',
    star: '<path d="m12 2 3 6.2 6.8 1-4.9 4.8 1.2 6.8L12 17.6l-6.1 3.2 1.2-6.8-4.9-4.8 6.8-1L12 2Z"/>',
    signal: '<path d="M12 19v-4m-4-2a6 6 0 0 1 8 0M5 10a10 10 0 0 1 14 0M2 7a14 14 0 0 1 20 0"/>',
    rocket: '<path d="M14 3c4 0 7 3 7 7-2 3-5 6-9 8L6 12c2-4 5-7 8-9ZM6 12l-3 1-1 4 4-1M12 18l-1 4 4-1 1-3M7 17l-2 3"/><circle cx="15" cy="9" r="2"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 5 5"/>'
  });

  function glyph(name) {
    return Object.prototype.hasOwnProperty.call(shapes, name)
      ? '<svg class="mono-icon-glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" focusable="false" aria-hidden="true">' + shapes[name] + '</svg>'
      : '';
  }
  function html(name) {
    return '<span class="mono-icon" aria-hidden="true">' + glyph(name) + '</span>';
  }
  function render(root) {
    (root || document).querySelectorAll('[data-icon]').forEach(function (el) {
      const image = glyph(el.getAttribute('data-icon'));
      if (image) { el.innerHTML = image; el.removeAttribute('data-icon'); }
    });
  }
  window.FuschIcons = {html: html, render: render};
  if (document.readyState === 'loading')
    document.addEventListener('DOMContentLoaded', function () { render(document); });
  else render(document);
})();
