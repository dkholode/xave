/* Runs only in the online browser. No native JS bridge is exposed to websites. */
(() => {
  // Same post, same collector: photo overlays and query changes keep posts that X has scrolled out of the page.
  const pageKey = href => {
    const url = new URL(href);
    const match = url.pathname.match(/^\/(?:[^/]+|i\/web)\/status(?:es)?\/(\d+)/);
    return match ? 'status:' + match[1] : url.origin + url.pathname;
  };
  if (window.__xaveCollector) {
    if (window.__xaveCollector.key === pageKey(location.href)) return;
    window.__xaveCollector.stop();
  }
  const source = location.href;
  const key = pageKey(source);
  const isX = /(^|\.)(x|twitter)\.com$/i.test(location.hostname);
  const status = location.pathname.match(/^\/(?:([^/]+)|i\/web)\/status(?:es)?\/(\d+)/);
  const posts = new Map();
  let author = status && status[1] && !['i', 'web'].includes(status[1]) ? status[1].toLowerCase() : '';
  const clean = s => (s || '').replace(/\u00a0/g, ' ').trim();
  // display:contents wrappers have no boxes of their own but still show their children.
  const visible = el => !!(el.getClientRects().length || getComputedStyle(el).display === 'contents')
    && getComputedStyle(el).visibility !== 'hidden';
  const image = el => ({kind: 'image', url: el.currentSrc || el.src,
    text: el.alt || 'Image'});

  // A post is named by its own timestamp link; links in its text, quoted posts, and ads don't count.
  function identify(card) {
    const own = selector => [...card.querySelectorAll(selector)].filter(el =>
      el.closest('article') === card && !el.closest('[data-testid="quoteTweet"]'));
    if (card.querySelector('[data-testid="placementTracking"]') || /\b(Ad|Promoted)\b/.test(card.querySelector('[data-testid="socialContext"]')?.innerText || '')) return null;
    const time = own('time')[0];
    // Anonymous X pages use semantic articles and timestamp links, without test IDs.
    const timestamp = time?.closest('a') || own('a[href*="ct=engagement_view_post"]')[0];
    const link = timestamp ? new URL(timestamp.getAttribute('href'), source).href : '';
    const match = link.match(/\/([^/]+)\/status\/(\d+)/);
    return match ? {card, own, time, timestamp, match} : null;
  }

  function scan() {
    if (!isX || !status || pageKey(location.href) !== key) return;
    const found = [...document.querySelectorAll('article')].map(identify).filter(Boolean);
    // Right after navigating to another post, X still shows the previous one, so wait until this post is on the page.
    if (!posts.has(status[2]) && !found.some(post => post.match[2] === status[2])) return;
    found.forEach(({card, own, time, timestamp, match}) => {
      if (match[2] === status[2]) author = match[1].toLowerCase();
      if (!author || match[1].toLowerCase() !== author) return;
      const text = own('[data-testid="tweetText"], [dir="auto"].whitespace-pre-wrap')[0];
      const blocks = [];
      // Empty text doesn't count, so a post X renders without readable content is refused instead of saved blank.
      if (text && visible(text) && !text.closest('[data-testid="quoteTweet"]') && clean(text.innerText))
        blocks.push({kind: 'text', text: clean(text.innerText)});
      // Photo containers inside quoted posts are deliberately excluded.
      own('[data-testid="tweetPhoto"] img, a[href*="/status/' + match[2] + '/photo/"] img').forEach(img => {
        if (visible(img)) blocks.push(image(img));
      });
      if (own('video').length) blocks.push({kind:'text', text:'[Video not saved offline — see original post]'});
      if (card.querySelector('[data-testid="quoteTweet"], article')) blocks.push({kind:'text', text:'[Quoted post — see original]'});
      if (!blocks.length) return;
      blocks.push({kind: 'link', text: time?.dateTime || clean(timestamp.innerText) || 'Original post',
        url: 'https://x.com/' + match[1] + '/status/' + match[2]});
      posts.set(match[2], {id: match[2], blocks});
    });
  }

  function article() {
    const xArticle = document.querySelector('[data-testid="twitterArticleReadView"]');
    // Anonymous status pages render the Article inline, marked by data-article-part instead of test IDs.
    const xBody = document.querySelector('[data-testid="twitterArticleRichTextView"], [data-article-part="body"]');
    const root = xArticle || xBody;
    const title = clean(document.querySelector('[data-testid="twitter-article-title"], [data-article-part="title"]')?.innerText
      || root.querySelector('h1')?.innerText || document.title);
    const blocks = [];
    const excluded = 'nav,footer,aside,script,style,noscript,form,button,[role="navigation"],[hidden],[aria-hidden="true"]';
    let pending = [], pendingContext = {}, skippedTitle = false;
    function flush() {
      while (pending.length && !pending[0].text.trim()) pending.shift();
      while (pending.length && !pending[pending.length-1].text.trim()) pending.pop();
      if (pending.length) {
        pending[0].text = pending[0].text.trimStart();
        pending[pending.length-1].text = pending[pending.length-1].text.trimEnd();
        const text = pending.map(r => r.text).join('');
        if (text) blocks.push({...pendingContext, text, runs: pending});
      }
      pending = [];
    }
    function append(text, marks, context) {
      if (!text) return;
      if (!pending.length) pendingContext = context;
      const last = pending[pending.length-1];
      if (last && ['bold','italic','code','url'].every(key => last[key] === marks[key])) last.text += text;
      else pending.push({...marks, text});
    }
    function walk(node, context = {kind:'text'}, marks = {}) {
      if (node.nodeType === Node.TEXT_NODE) {
        append(node.textContent.replace(/\s+/g, ' '), marks, context);
        return;
      }
      if (node.nodeType !== Node.ELEMENT_NODE || node.matches(excluded) || !visible(node)) return;
      if (xArticle && (node.matches('[data-testid="twitter-article-title"]')
          || node.getAttribute('role') === 'group' && !xBody?.contains(node))) return;
      const tag = node.tagName;
      if (/^H[1-6]$/.test(tag) && !skippedTitle && clean(node.innerText) === title) { skippedTitle = true; return; }
      if (tag === 'IMG') {
        flush(); if (node.naturalWidth >= 120) blocks.push(image(node)); return;
      }
      if (tag === 'VIDEO' || tag === 'IFRAME' || tag === 'AUDIO') {
        flush(); blocks.push({kind:'text', text:'[Embedded media not saved offline — see original]'}); return;
      }
      if (tag === 'BR') { append('\n', marks, context); return; }
      if (tag === 'PRE') {
        flush(); blocks.push({kind:'code', text:node.innerText.replace(/^\n|\n$/g, '')}); return;
      }
      if (tag === 'UL' || tag === 'OL') {
        flush();
        let number = Number(node.getAttribute('start')) || 1;
        const depth = Number(context.depth || 0) + 1;
        [...node.children].forEach(li => {
          if (li.tagName !== 'LI') return;
          if (li.hasAttribute('value')) number = Number(li.getAttribute('value')) || number;
          walk(li, {kind:'listItem', depth, marker:tag === 'OL' ? (number++) + '.' : '•'});
          flush();
        });
        return;
      }
      let next = context;
      if (/^H[1-6]$/.test(tag)) next = {kind:'heading', level:Math.max(2,Number(tag[1]))};
      if (tag === 'BLOCKQUOTE') next = {kind:'quote'};
      const boundary = /^(P|DIV|SECTION|ARTICLE|MAIN|BLOCKQUOTE|LI|H[1-6]|TR|TD|TH)$/.test(tag)
        || /^(block|flex|grid|table-row)$/.test(getComputedStyle(node).display);
      if (boundary) flush();
      const style = {...marks};
      const computed = getComputedStyle(node);
      if (Number(computed.fontWeight) >= 600 || computed.fontWeight === 'bold') style.bold = true;
      if (computed.fontStyle === 'italic') style.italic = true;
      if (tag === 'STRONG' || tag === 'B') style.bold = true;
      if (tag === 'EM' || tag === 'I') style.italic = true;
      if (tag === 'CODE') style.code = true;
      if (tag === 'A' && node.hasAttribute('href')) try {
        const link = new URL(node.getAttribute('href'), source);
        if (/^https?:$/.test(link.protocol) && !link.username && !link.password) style.url = link.href;
      } catch (_) { }
      node.childNodes.forEach(child => walk(child, next, style));
      if (boundary) flush();
    }
    walk(root); flush();
    return {title, blocks};
  }

  const articleView = '[data-testid="twitterArticleReadView"], [data-testid="twitterArticleRichTextView"], [data-article-part="body"]';
  // Polled by the app so the capture screen can say how much of the thread has loaded, and so it can
  // save without a tap once the linked post or the Article's text stops changing.
  function summary() {
    if (pageKey(location.href) !== key) return {article: false, status: false, posts: 0, linked: false, articleLength: 0};
    scan();
    const view = isX && document.querySelector(articleView);
    return {article: !!view, status: !!status, posts: posts.size, linked: !!status && posts.has(status[2]),
      articleLength: view ? view.innerText.length : 0};
  }

  function capture() {
    if (pageKey(location.href) !== key) return JSON.stringify({error:'The page changed. Reload the page before capturing this link.'});
    if (!isX) return JSON.stringify({error:'Xave saves X posts and Articles only. Open an x.com link to capture it.'});
    scan();
    if (status && !document.querySelector(articleView)) {
      if (!posts.has(status[2])) return JSON.stringify({error: 'The linked post is not loaded. Sign in if needed, open the post, then try again.'});
      // Snowflake IDs sort chronologically without floating-point precision loss.
      const ordered = [...posts.values()].sort((a,b) => a.id.length-b.id.length || a.id.localeCompare(b.id));
      return JSON.stringify({title: '@' + author + ' · ' + ordered.length + (ordered.length === 1 ? ' post' : ' posts'),
        source, blocks: ordered.flatMap((p,i) => i ? [{kind:'divider'}, ...p.blocks] : p.blocks)});
    }
    if (!document.querySelector(articleView)) return JSON.stringify({error:'Open a post or an X Article, then try again.'});
    const result = article();
    if (result.blocks.filter(b => b.kind !== 'image').map(b => b.text || '').join('').length < 100)
      return JSON.stringify({error:'Not enough readable text found. Wait for the article to load or open its full reading view, then try again.'});
    return JSON.stringify({...result, source});
  }
  scan();
  const timer = setInterval(scan, 800);
  window.addEventListener('pagehide', () => clearInterval(timer), {once:true});
  window.__xaveCollector = {capture, summary, source, key, stop: () => clearInterval(timer)};
})();
