/* Browser-based regression tests using the actual capture script and DOM fixtures. */
const results = [];
function assert(condition, message) { if (!condition) throw new Error(message); }
function fixture(html, url = 'https://example.org/post') {
  const frame = document.createElement('iframe');
  document.body.append(frame);
  const doc = frame.contentDocument;
  doc.open(); doc.write('<!doctype html><html><body>' + html + '</body></html>'); doc.close();
  const win = frame.contentWindow;
  const location = new URL(url);
  const install = () => new Function('window', 'document', 'location', 'getComputedStyle', 'Node', 'setInterval', 'clearInterval', window.captureSource)(win, doc, location, win.getComputedStyle.bind(win), win.Node, () => 1, () => {});
  install();
  return {doc, location, install, capture: () => JSON.parse(win.__xaveCollector.capture()), summary: () => win.__xaveCollector.summary(), close: () => frame.remove()};
}
function test(name, fn) {
  try { fn(); results.push('PASS · ' + name); }
  catch (e) { results.push('FAIL · ' + name + ': ' + e.message); }
}
const paragraph = 'An article should remain readable without its navigation, ads, or scripts. '.repeat(5);
const post = (who, id, text) => `<article data-testid="tweet"><div data-testid="User-Name">${who}</div><a href="https://x.com/${who}/status/${id}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText">${text}</div></article>`;
const root = '20000000000000000001';
const reply = '20000000000000000002';

test('Blog headings, lists, and code survive; navigation and hidden text are excluded', () => {
  const f = fixture(`<nav>Navigation junk</nav><article><h1>A useful article</h1><p>${paragraph}</p><h2>Details</h2><ul><li>First point</li></ul><pre>let x = 1;</pre><div hidden>Hidden junk</div><aside>Advert junk</aside><script type="application/json">Script junk</script></article>`);
  const doc = f.capture();
  assert(doc.title === 'A useful article', 'Wrong title');
  assert(doc.blocks.some(b => b.kind === 'code' && b.text.includes('let x')), 'Lost code');
  const text = JSON.stringify(doc.blocks);
  assert(text.includes('First point') && text.includes('Details'), 'Lost article blocks');
  assert(!text.includes('junk'), 'Captured page clutter');
  f.close();
});
test('X Articles use their article title and content instead of Conversation or page controls', () => {
  const f = fixture(`<main><h1>Conversation</h1><div>Outer page clutter</div><article data-testid="twitterArticleReadView"><div data-testid="twitter-article-title">Train a small classifier</div><div role="group" aria-label="Engagement"><a>825K views</a><button>Repost</button></div><div data-testid="twitterArticleRichTextView"><h1>Getting started</h1><p>${paragraph}</p><h2>Wrapping up</h2><p>Final article paragraph.</p></div></article><aside>${paragraph.repeat(3)}</aside></main>`, `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(doc.title === 'Train a small classifier', 'Chose page chrome or a section heading as the title');
  const text = JSON.stringify(doc.blocks);
  assert(text.includes('Final article paragraph.'), 'Lost the article ending');
  assert(!text.includes('Conversation') && !text.includes('Outer page clutter') && !text.includes('825K views'), 'Captured page controls');
  f.close();
});
test('Short login/error pages produce an explicit capture error', () => {
  const f = fixture('<main><h1>Sign in</h1><p>Please sign in first.</p></main>');
  assert(!!f.capture().error, 'Login page was treated as an article'); f.close();
});
test('X retains exact snowflake IDs and excludes replies by other authors', () => {
  const f = fixture(post('alice', reply, 'Second post') + post('bob', '20000000000000000003', 'Unrelated reply') + post('alice', root, 'First post'), `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(doc.title === '@alice · 2 posts', 'Wrong post count');
  const texts = doc.blocks.filter(b => b.kind === 'text').map(b => b.text);
  assert(texts.join('|') === 'First post|Second post', 'Wrong author or precision/order loss');
  assert(doc.warning.startsWith('Partial capture:'), 'Missing partial-capture warning'); f.close();
});
test('Loading status reports X Articles, loaded posts, and pages that are not posts', () => {
  const thread = fixture(post('alice', root, 'First post') + post('alice', reply, 'Second post') + post('bob', '20000000000000000003', 'Reply'), `https://x.com/alice/status/${root}`);
  assert(JSON.stringify(thread.summary()) === '{"article":false,"status":true,"posts":2}', 'Wrong thread status: ' + JSON.stringify(thread.summary())); thread.close();
  const article = fixture(`<article data-testid="twitterArticleReadView"><p>${paragraph}</p></article>`, `https://x.com/alice/status/${root}`);
  assert(article.summary().article === true, 'Did not recognize an X Article'); article.close();
  const profile = fixture(post('alice', root, 'Pinned post'), 'https://x.com/alice');
  assert(JSON.stringify(profile.summary()) === '{"article":false,"status":false,"posts":0}', 'Treated a profile as a post'); profile.close();
});
test('X refuses capture when the linked post has not loaded', () => {
  const f = fixture(post('alice', reply, 'Only a reply'), `https://x.com/alice/status/${root}`);
  assert(!!f.capture().error, 'Accepted a thread without its focal post'); f.close();
});
test('X i/web share links infer the author from the linked post', () => {
  const f = fixture(post('alice', root, 'Shared post') + post('alice', reply, 'Reply'), `https://x.com/i/web/status/${root}`);
  assert(f.capture().title === '@alice · 2 posts', 'Could not resolve shared status URL'); f.close();
});
test('X accumulates virtualized posts and replaces expanded text without duplicates', () => {
  const f = fixture(post('alice', root, 'Truncated'), `https://x.com/alice/status/${root}`);
  f.doc.body.innerHTML = post('alice', root, 'Expanded full post'); f.capture();
  f.doc.body.innerHTML = post('alice', reply, 'Newly loaded reply');
  const doc = f.capture();
  assert(doc.title === '@alice · 2 posts', 'Lost virtualized root or duplicated post');
  assert(doc.blocks.some(b => b.text === 'Expanded full post'), 'Expansion was not retained'); f.close();
});
test('Navigating to another post resets the collector', () => {
  const f = fixture(post('alice', root, 'First thread'), `https://x.com/alice/status/${root}`);
  f.location.href = `https://x.com/bob/status/${reply}`;
  f.doc.body.innerHTML = post('bob', reply, 'Different thread'); f.install();
  const doc = f.capture();
  assert(doc.title === '@bob · 1 post' && !JSON.stringify(doc).includes('First thread'), 'Leaked posts between threads'); f.close();
});
test('X preserves image-only posts in a thread', () => {
  const photo = `<article data-testid="tweet"><a href="https://x.com/alice/status/${reply}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetPhoto"><img src="https://pbs.twimg.com/media/test.jpg" alt="A diagram"></div></article>`;
  const f = fixture(post('alice', root, 'Thread introduction') + photo, `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(doc.title === '@alice · 2 posts', 'Dropped an image-only reply');
  assert(doc.blocks.some(b => b.kind === 'image' && b.text === 'A diagram'), 'Lost the reply image'); f.close();
});
test('X does not attribute quoted text or images to the posting author', () => {
  const quote = `<article data-testid="tweet"><a href="https://x.com/alice/status/${root}"><time>Today</time></a><div data-testid="quoteTweet"><div data-testid="tweetText">Someone else said this</div><div data-testid="tweetPhoto"><img src="https://example.org/quote.jpg"></div></div></article>`;
  const f = fixture(quote, `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(!JSON.stringify(doc.blocks).includes('Someone else said this'), 'Misattributed quoted text');
  assert(!doc.blocks.some(b => b.kind === 'image'), 'Captured quoted image');
  assert(doc.blocks.some(b => b.text.includes('Quoted post')), 'Missing quote placeholder'); f.close();
});
test('Anonymous X articles preserve the public post and photo without test IDs', () => {
  const f = fixture(`<main><article><div dir="auto" class="whitespace-pre-wrap">Public post text</div><a href="/alice/status/${root}/photo/1"><img src="https://pbs.twimg.com/media/photo.webp"></a><a href="https://m.x.com/alice/status/${root}?launch_app_store=true&ct=engagement_view_post">Today</a><div data-engagement-action="like">123 likes</div></article></main>`, `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(doc.title === '@alice · 1 post', 'Anonymous post not recognized');
  assert(doc.blocks.some(b => b.text === 'Public post text'), 'Missing public text');
  assert(doc.blocks.some(b => b.kind === 'image'), 'Missing public photo');
  assert(!JSON.stringify(doc.blocks).includes('123 likes'), 'Captured engagement UI');
  assert(doc.blocks.some(b => b.url === `https://x.com/alice/status/${root}`), 'Original link is not canonical'); f.close();
});
test('Anonymous X refuses an article without a post identity', () => {
  const f = fixture('<article><div dir="auto" class="whitespace-pre-wrap">Unidentified post</div></article>', `https://x.com/alice/status/${root}`);
  assert(!!f.capture().error, 'Captured unidentified content'); f.close();
});

test('Inline text, emphasis, and links stay in one paragraph in DIV-based articles', () => {
  const f = fixture(`<article><h1>Rich text</h1><p>${paragraph}</p><div>Read <strong>this important</strong> sentence with <a href="/reference">a reference</a> here.</div></article>`);
  const doc = f.capture();
  const block = doc.blocks.find(b => b.text === 'Read this important sentence with a reference here.');
  assert(!!block, 'Split one sentence into separate paragraphs');
  assert(block.runs.some(r => r.bold && r.text === 'this important'), 'Lost emphasis');
  assert(block.runs.some(r => r.url === 'https://example.org/reference'), 'Lost or failed to resolve the inline link');
  f.close();
});
test('Excluded descendants inside paragraphs never leak into saved text', () => {
  const f = fixture(`<article><h1>Clean article</h1><p>${paragraph}<span aria-hidden="true">HIDDEN_CONTROL</span><button>AD_CONTROL</button><span hidden>HIDDEN_ELEMENT</span><span style="display:none">CSS_HIDDEN</span> Visible ending.</p></article>`);
  const text = JSON.stringify(f.capture().blocks);
  assert(!/HIDDEN_CONTROL|AD_CONTROL|HIDDEN_ELEMENT|CSS_HIDDEN/.test(text), 'Captured excluded paragraph descendants');
  assert(text.includes('Visible ending.'), 'Removed readable content'); f.close();
});
test('Numbered and nested lists, heading levels, quotes, and code indentation survive', () => {
  const f = fixture(`<article><h1>Structure</h1><p>${paragraph}</p><h2>Section</h2><h3>Detail</h3><ol start="3"><li>First step<ul><li>Nested point</li></ul></li><li value="7">Second step</li></ol><blockquote>A useful quote.</blockquote><pre>  indented\n    further</pre></article>`);
  const blocks = f.capture().blocks;
  assert(blocks.some(b => b.kind === 'heading' && b.level === 3 && b.text === 'Detail'), 'Lost heading hierarchy');
  assert(blocks.some(b => b.kind === 'listItem' && b.marker === '3.' && b.text === 'First step'), 'Lost ordered list start');
  assert(blocks.some(b => b.kind === 'listItem' && b.marker === '7.'), 'Lost list value');
  assert(blocks.some(b => b.kind === 'listItem' && b.depth === 2 && b.text === 'Nested point'), 'Lost nested list');
  assert(blocks.some(b => b.kind === 'quote' && b.text === 'A useful quote.'), 'Lost quote');
  assert(blocks.some(b => b.kind === 'code' && b.text.startsWith('  indented')), 'Trimmed code indentation'); f.close();
});
test('Article title is emitted once rather than repeated as a body heading', () => {
  const f = fixture(`<article><h1>Unique title</h1><p>${paragraph}</p><h2>Another heading</h2></article>`);
  const doc = f.capture();
  assert(doc.title === 'Unique title', 'Wrong title');
  assert(!doc.blocks.some(b => b.kind === 'heading' && b.text === doc.title), 'Duplicated title'); f.close();
});
test('Semantic article is preferred over a larger surrounding discussion region', () => {
  const f = fixture(`<article><h1>The article</h1><p>REAL_ARTICLE ${paragraph}</p></article><main><h1>Discussion</h1><p>${'UNRELATED_COMMENT '.repeat(150)}</p></main>`);
  const doc = f.capture();
  assert(doc.title === 'The article' && JSON.stringify(doc.blocks).includes('REAL_ARTICLE'), 'Chose discussion instead of article');
  assert(!JSON.stringify(doc.blocks).includes('UNRELATED_COMMENT'), 'Included unrelated discussion'); f.close();
});
test('Active and credential-bearing inline links are kept as inert text', () => {
  const f = fixture(`<article><h1>Safe links</h1><p>${paragraph}<a href="javascript:alert(1)">Unsafe JS</a> <a href="https://name:secret@example.org/">Private credentials</a></p></article>`);
  const doc = f.capture();
  assert(doc.blocks.some(b => b.text.includes('Unsafe JS')), 'Lost the visible link text');
  assert(!doc.blocks.flatMap(b => b.runs || []).some(r => r.url), 'Retained an unsafe link'); f.close();
});
test('Explicit line breaks stay inside their paragraph', () => {
  const f = fixture(`<article><h1>Line breaks</h1><p>${paragraph}</p><div>Before<br>After</div></article>`);
  assert(f.capture().blocks.some(b => b.text === 'Before\nAfter'), 'Lost the explicit line break'); f.close();
});
test('Styled spans retain emphasis without copying source styles or inventing anchor links', () => {
  const f = fixture(`<article><h1>Styled spans</h1><p>${paragraph}</p><div>A <span style="font-weight:700">bold</span> and <span style="font-style:italic">italic</span> sentence with <a name="section">an anchor</a>.</div></article>`);
  const block = f.capture().blocks.find(b => b.text === 'A bold and italic sentence with an anchor.');
  assert(block && block.runs.some(r => r.bold && r.text === 'bold'), 'Lost CSS-based emphasis');
  assert(block.runs.some(r => r.italic && r.text === 'italic'), 'Lost CSS-based italics');
  assert(!block.runs.some(r => r.url), 'Invented a URL for a named anchor'); f.close();
});
document.getElementById('results').textContent = results.join('\n');
document.title = 'Xave capture: ' + results.filter(x => x.startsWith('PASS')).length + '/' + results.length + ' passed' + (results.some(x => x.startsWith('FAIL')) ? ' FAILED' : '');
document.getElementById('summary').textContent = results.filter(x => x.startsWith('PASS')).length + '/' + results.length + ' passed';
