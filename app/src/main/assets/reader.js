/* Troop Reader: isolated offline DOM navigation. No credentials or native bridge. */
(() => {
  // Some Android WebViews initially resolve vh to zero before attachment/layout.
  window.troopViewport = () => {
    const height = window.innerHeight || document.documentElement.clientHeight;
    if (height > 0) document.documentElement.style.setProperty('--reader-height', height + 'px');
  };
  window.addEventListener('resize', window.troopViewport);
  if (window.visualViewport) window.visualViewport.addEventListener('resize', window.troopViewport);
  requestAnimationFrame(window.troopViewport);
  // Image mode keeps a single renderer. Swap already-decoded nodes atomically;
  // prefetch only neighbouring spreads, never the entire chapter or remote URLs.
  const images = document.querySelector('.images');
  if (images) {
    let total = Number(images.dataset.total); const step = Number(images.dataset.step);
    window.troopAvailable = count => { if(Number.isInteger(count) && count > total) { total = count; images.dataset.total = String(count); } };
    let current = Number(images.dataset.page), request = 0, result = 1;
    const cache = new Map();
    const pixelBudget = 16 * 1024 * 1024;
    function prepare(page) {
      if (cache.has(page)) return cache.get(page);
      const entry = {nodes: [], ready: false, pixels: 0};
      for (let p = page; p < Math.min(page + step, total); p++) {
        const img = new Image(); img.alt = 'Página ' + (p + 1); img.src = p + '.img';
        entry.nodes.push(img);
      }
      entry.promise = Promise.all(entry.nodes.map(img => img.decode())).then(() => {
        entry.pixels = entry.nodes.reduce((n, img) => n + img.naturalWidth * img.naturalHeight, 0);
        entry.ready = true;
        return entry;
      });
      // Failed speculative loads must not produce unhandled rejections or stick.
      entry.promise.catch(() => { if (cache.get(page) === entry) cache.delete(page); });
      cache.set(page, entry);
      return entry;
    }
    function neighbours() {
      const keep = new Set([current, current + step, current - step]);
      for (const p of cache.keys()) if (!keep.has(p)) cache.delete(p);
      let pixels = cache.get(current)?.pixels || 0;
      // Serial predecode bounds concurrent work; very large pages skip prefetch.
      const origin = current;
      (async () => {
        for (const p of [origin + step, origin - step]) {
          if (origin !== current || pixels >= pixelBudget) break;
          if (p < 0 || p >= total) continue;
          try {
            const entry = await prepare(p).promise;
            pixels += entry.pixels;
            if (p !== current && (origin !== current || pixels > pixelBudget)) cache.delete(p);
          } catch (_) { /* The visible page is untouched. Retry on explicit navigation. */ }
        }
      })();
    }
    const initial = {nodes: [...images.children], ready: true, pixels: 0};
    initial.promise = Promise.all(initial.nodes.map(img => img.decode())).then(() => {
      initial.pixels = initial.nodes.reduce((n, img) => n + img.naturalWidth * img.naturalHeight, 0);
      if (current === Number(images.dataset.page)) neighbours();
      return initial;
    });
    initial.promise.catch(() => {});
    cache.set(current, initial);
    window.troopImageResult = token => token === request ? result : -1;
    window.troopCancelImages = token => { if (token === request) { request++; result = -1; } };
    window.troopShowImages = (page, token) => {
      request = token; result = 0;
      if (!Number.isInteger(page) || page < 0 || page >= total) return (result = -1);
      const commit = entry => {
        if (request !== token) return;
        images.replaceChildren(...entry.nodes);
        current = page; images.dataset.page = String(page);
        window.scrollTo(0, 0); window.troopViewport();
        result = 1;
        // Defer prefetch until the changed image has had a rendering opportunity.
        requestAnimationFrame(() => requestAnimationFrame(neighbours));
      };
      const entry = prepare(page);
      if (entry.ready) commit(entry);
      else entry.promise.then(commit).catch(() => { if (request === token) result = -1; });
      return result;
    };
  }
  const root = () => { const content = document.querySelector('.book-content'); return content && content.firstElementChild; };
  window.troopPosition = () => {
    const r = root(); if (!r) return '';
    const blocks = [...r.querySelectorAll('p,h1,h2,h3,h4,li,blockquote')];
    const candidates = blocks.length ? blocks : [...r.querySelectorAll('div')].filter(e => !e.querySelector('div'));
    const el = candidates.find(e => e.getBoundingClientRect().bottom > 24 && e.getBoundingClientRect().top < innerHeight) || r;
    if (el.id && !/["']/.test(el.id)) return 'id("' + el.id + '")';
    let path = '', n = el;
    while (n && n !== r) {
      const siblings = [...n.parentElement.children].filter(s => s.tagName === n.tagName);
      path = '/' + n.tagName.toLowerCase() + '[' + (siblings.indexOf(n) + 1) + ']' + path;
      n = n.parentElement;
    }
    return '//body' + path;
  };
  window.troopRestore = position => {
    const r = root(); if (!r || !position) return;
    if(position === 'troop:end') { window.scrollTo(0, document.documentElement.scrollHeight); return; }
    let el;
    try {
      if (position.startsWith('#')) el = document.getElementById(position.slice(1));
      else if (position.startsWith('id(')) el = document.evaluate(position, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
      else {
        const relative = position.replace(/^\/\/body|^\/body/, '');
        el = document.evaluate('.' + relative, r, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
      }
      if (el && r.contains(el)) el.scrollIntoView({block:'start'});
    } catch (_) { /* Unknown positions stay at start; never execute server content. */ }
  };
  document.addEventListener('click', event => {
    const a = event.target.closest('a'); if (!a) return;
    event.preventDefault();
    const page = a.getAttribute('kavita-page'), part = a.getAttribute('kavita-part') || '';
    if (page !== null && /^\d+$/.test(page)) location.href = '/goto?page=' + page + '#' + encodeURIComponent(part.replace(/^#/, ''));
    else if ((a.getAttribute('href') || '').startsWith('#')) window.troopRestore(a.getAttribute('href'));
  });
})();

/* Local EPUB tools. Text is passed as JSON and inserted as text nodes, never HTML. */
(() => {
  const container = () => document.querySelector('.book-content');
  const selector = 'p,h1,h2,h3,h4,li,blockquote';
  const blocks = () => {
    const c = container(); if (!c) return [];
    let b = [...c.querySelectorAll(selector)].filter(e => !e.querySelector(selector));
    if (!b.length) b = [...c.querySelectorAll('div')].filter(e => !e.querySelector('div'));
    return b.length ? b : [c];
  };
  function rangeAt(el, start, end) {
    const walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let node, offset = 0, a, z;
    while ((node = walk.nextNode())) {
      const next = offset + node.length;
      if (!a && start >= offset && start <= next) a = [node, start - offset];
      if (end >= offset && end <= next) { z = [node, end - offset]; break; }
      offset = next;
    }
    if (!a || !z) return null;
    const range = document.createRange(); range.setStart(...a); range.setEnd(...z); return range;
  }
  let lastSelection = null;
  const currentSelection = () => {
    const selected = getSelection(); if (!selected || selected.isCollapsed || !selected.rangeCount) return null;
    const r = selected.getRangeAt(0), b = blocks();
    const index = b.findIndex(e => e.contains(r.startContainer) && e.contains(r.endContainer));
    if(index < 0) return {error:'Selecciona texto dentro de un solo párrafo.'};
    const before = document.createRange(); before.selectNodeContents(b[index]); before.setEnd(r.startContainer,r.startOffset);
    const start = before.toString().length, quote = r.toString();
    return {block:index,start,end:start+quote.length,quote};
  };
  document.addEventListener('selectionchange', () => {
    const value = currentSelection();
    if(value && value.quote) lastSelection = value;
  });
  window.troopSelection = () => currentSelection() || lastSelection;
  window.troopHighlights = notes => {
    const c = container(); if(!c) return;
    c.querySelectorAll('mark[data-troop-note]').forEach(m => m.replaceWith(...m.childNodes));
    c.normalize(); const b = blocks();
    // Reverse ranges keep offsets stable, including multiple notes in one paragraph.
    [...notes].sort((a,z) => z.start-a.start).forEach(n => {
      const el=b[n.block]; if(!el || el.textContent.slice(n.start,n.end)!==n.quote) return;
      const r=rangeAt(el,n.start,n.end); if(!r) return;
      const mark=document.createElement('mark'); mark.dataset.troopNote=n.id;
      mark.appendChild(r.extractContents()); r.insertNode(mark);
    });
  };
  const restore = window.troopRestore;
  window.troopRestore = position => {
    const m = /^@text:(\d+):(\d+):(\d+)$/.exec(position || '');
    if(!m) return restore(position);
    const el = blocks()[Number(m[1])]; if(!el) return;
    el.scrollIntoView({block:'center'});
    const range = rangeAt(el,Number(m[2]),Number(m[3]));
    if(range) { const s=getSelection();s.removeAllRanges();s.addRange(range); }
  };
  window.troopVisibleBlock = () => Math.max(0, blocks().findIndex(e => e.getBoundingClientRect().bottom > 24));
})();
