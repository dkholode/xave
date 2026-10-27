/* Browser-based regression tests using the actual capture script and DOM fixtures. */
const results = [];
function assert(condition, message) { if (!condition) throw new Error(message); }
function assertRuns(runs, expected) {
  const same = runs.length === expected.length && expected.every((run, i) => runs[i].text === run.text && runs[i].url === run.url && Object.keys(runs[i]).every(key => key === 'text' || key === 'url'));
  assert(same, 'Wrong runs: ' + JSON.stringify(runs));
}
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
// An X Article as it appears once its full reading view is open.
const xArticle = (title, body) => fixture(`<main><h1>Conversation</h1><article data-testid="twitterArticleReadView"><div data-testid="twitter-article-title">${title}</div><div data-testid="twitterArticleRichTextView">${body}</div></article></main>`, `https://x.com/alice/status/${root}`);

test('X Article headings, lists, and code survive; navigation and hidden text are excluded', () => {
  const f = xArticle('A useful article', `<nav>Navigation junk</nav><p>${paragraph}</p><h2>Details</h2><ul><li>First point</li></ul><pre>let x = 1;</pre><div hidden>Hidden junk</div><aside>Advert junk</aside><script type="application/json">Script junk</script>`);
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
test('Login pages and pages outside X produce an explicit capture error', () => {
  const login = fixture('<main><h1>Sign in</h1><p>Please sign in first.</p></main>', 'https://x.com/i/flow/login');
  assert(!!login.capture().error, 'Login page was treated as an article'); login.close();
  const blog = fixture(`<article><h1>A blog post</h1><p>${paragraph}</p></article>`);
  assert(!!blog.capture().error, 'Captured a page outside X'); blog.close();
});
test('X retains exact snowflake IDs and excludes replies by other authors', () => {
  const f = fixture(post('alice', reply, 'Second post') + post('bob', '20000000000000000003', 'Unrelated reply') + post('alice', root, 'First post'), `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(doc.title === '@alice · 2 posts', 'Wrong post count');
  const texts = doc.blocks.filter(b => b.kind === 'text').map(b => b.text);
  assert(texts.join('|') === 'First post|Second post', 'Wrong author or precision/order loss');
  f.close();
});
test('Loading status reports X Articles, loaded posts, and pages that are not posts', () => {
  const thread = fixture(post('alice', root, 'First post') + post('alice', reply, 'Second post') + post('bob', '20000000000000000003', 'Reply'), `https://x.com/alice/status/${root}`);
  assert(JSON.stringify(thread.summary()) === '{"article":false,"status":true,"posts":2,"linked":true,"articleLength":0}', 'Wrong thread status: ' + JSON.stringify(thread.summary())); thread.close();
  const article = fixture(`<article data-testid="twitterArticleReadView"><p>${paragraph}</p></article>`, `https://x.com/alice/status/${root}`);
  assert(article.summary().article === true, 'Did not recognize an X Article'); article.close();
  const profile = fixture(post('alice', root, 'Pinned post'), 'https://x.com/alice');
  assert(JSON.stringify(profile.summary()) === '{"article":false,"status":false,"posts":0,"linked":false,"articleLength":0}', 'Treated a profile as a post'); profile.close();
});
test('Loading status says when the linked post has loaded and how much Article text there is', () => {
  const f = fixture(post('alice', reply, 'A later post'), `https://x.com/alice/status/${root}`);
  assert(f.summary().linked === false, 'Counted another post as the linked one');
  f.doc.body.insertAdjacentHTML('afterbegin', post('alice', root, 'The linked post'));
  assert(f.summary().linked === true, 'Missed the linked post'); f.close();
  const article = fixture(`<article data-testid="twitterArticleReadView"><p>Short opening.</p></article>`, `https://x.com/alice/status/${root}`);
  assert(article.summary().articleLength === 'Short opening.'.length, 'Wrong Article length: ' + article.summary().articleLength);
  article.doc.querySelector('p').insertAdjacentHTML('afterend', '<p>More text.</p>');
  assert(article.summary().articleLength > 'Short opening.'.length, 'Article length did not grow as text loaded'); article.close();
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
test('A collapsed re-render never replaces an expanded post', () => {
  const f = fixture(post('alice', root, 'All of the already expanded content'), `https://x.com/alice/status/${root}`);
  f.summary();
  f.doc.querySelector('[data-testid="tweetText"]').textContent = 'All of…';
  f.summary();
  const texts = f.capture().blocks.filter(b => b.kind === 'text').map(b => b.text);
  assert(texts.includes('All of the already expanded content'), 'Collapsed re-render replaced the expanded post');
  assert(!texts.includes('All of…'), 'Kept the collapsed text'); f.close();
});
test('A quoted post that loads after the post collapses does not replace the expanded text', () => {
  const f = fixture(post('alice', root, 'All of the already expanded content'), `https://x.com/alice/status/${root}`);
  f.summary();
  f.doc.querySelector('[data-testid="tweetText"]').textContent = 'All of…';
  f.doc.querySelector('article').insertAdjacentHTML('beforeend', '<div data-testid="quoteTweet"><div data-testid="tweetText">Quoted words</div></div>');
  f.summary();
  const blocks = f.capture().blocks;
  assert(blocks.some(b => b.kind === 'text' && b.text === 'All of the already expanded content'), 'Collapsed text replaced the expanded text: ' + JSON.stringify(blocks));
  assert(blocks.some(b => b.text === '[Quoted post — see original]'), 'Lost the quoted post note: ' + JSON.stringify(blocks)); f.close();
});
test('A photo that loads after the post collapses is kept with the expanded text', () => {
  const f = fixture(post('alice', root, 'All of the already expanded content'), `https://x.com/alice/status/${root}`);
  f.summary();
  f.doc.querySelector('[data-testid="tweetText"]').textContent = 'All of…';
  f.doc.querySelector('article').insertAdjacentHTML('beforeend', '<div data-testid="tweetPhoto"><img src="https://pbs.twimg.com/media/late.jpg" alt="A late photo"></div>');
  f.summary();
  const blocks = f.capture().blocks;
  assert(blocks.some(b => b.kind === 'text' && b.text === 'All of the already expanded content'), 'Lost the expanded text: ' + JSON.stringify(blocks));
  assert(blocks.some(b => b.kind === 'image' && b.url === 'https://pbs.twimg.com/media/late.jpg'), 'Lost the late photo: ' + JSON.stringify(blocks)); f.close();
});
test('Navigating to another post resets the collector', () => {
  const f = fixture(post('alice', root, 'First thread'), `https://x.com/alice/status/${root}`);
  f.location.href = `https://x.com/bob/status/${reply}`;
  f.doc.body.innerHTML = post('bob', reply, 'Different thread'); f.install();
  const doc = f.capture();
  assert(doc.title === '@bob · 1 post' && !JSON.stringify(doc).includes('First thread'), 'Leaked posts between threads'); f.close();
});
test('A new post collects nothing until X replaces the previous post on the page', () => {
  const f = fixture(post('alice', root, 'Post A'), `https://x.com/alice/status/${root}`);
  f.summary();
  f.location.href = `https://x.com/alice/status/${reply}`; f.install(); f.summary();
  f.doc.body.innerHTML = post('alice', reply, 'Post B');
  const doc = f.capture();
  assert(doc.title === '@alice · 1 post' && !JSON.stringify(doc).includes('Post A'), 'Kept the previous post from before X rendered the new one'); f.close();
});
test('A link to the new post inside the previous post does not count as the new post loading', () => {
  const f = fixture(post('alice', root, `Post A, see <a href="/alice/status/${reply}">this</a>`), `https://x.com/alice/status/${root}`);
  f.summary();
  f.location.href = `https://x.com/alice/status/${reply}`; f.install(); f.summary();
  f.doc.body.innerHTML = post('alice', reply, 'Post B');
  const doc = f.capture();
  assert(doc.title === '@alice · 1 post' && !JSON.stringify(doc).includes('Post A'), 'A text link unlocked collection of the previous post'); f.close();
});
test('Re-injecting on the same post keeps posts X has scrolled away', () => {
  const f = fixture(post('alice', root, 'First post') + post('alice', reply, 'Second post'), `https://x.com/alice/status/${root}`);
  f.summary();
  f.location.href = `https://x.com/alice/status/${root}/photo/1?s=20`;
  f.doc.body.innerHTML = post('alice', reply, 'Second post'); f.install();
  assert(f.capture().title === '@alice · 2 posts', 'Lost posts when the URL changed on the same post'); f.close();
});
test('A shared i/web link keeps its collector when X rewrites it to the author path', () => {
  const f = fixture(post('alice', root, 'Shared post'), `https://x.com/i/web/status/${root}`);
  f.summary();
  f.location.href = `https://x.com/alice/status/${root}`;
  f.doc.body.innerHTML = ''; f.install();
  assert(f.summary().posts === 1 && !f.capture().error, 'Reset or refused the same post after a URL rewrite'); f.close();
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
test('A quote without its own timestamp link stays unlinked', () => {
  const f = fixture(`<article data-testid="tweet"><a href="https://x.com/alice/status/${root}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText">Look</div><div data-testid="quoteTweet"><div data-testid="tweetText">See <a href="/carol/status/789">this</a></div></div></article>`, `https://x.com/alice/status/${root}`);
  const placeholder = f.capture().blocks.find(b => b.text === '[Quoted post — see original]');
  assert(placeholder && !placeholder.url, 'Linked the quote to a post mentioned in its text: ' + JSON.stringify(placeholder)); f.close();
});
test('A quoted post placeholder links to the quoted post', () => {
  const f = fixture(`<article data-testid="tweet"><div data-testid="User-Name">alice</div><a href="https://x.com/alice/status/${root}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText">Look at this</div><div data-testid="quoteTweet"><a href="/bob/status/30000000000000000003"><time datetime="2026-10-19">Yesterday</time></a><div data-testid="tweetText">Quoted words</div></div></article>`, `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  const placeholder = doc.blocks.find(b => b.text === '[Quoted post — see original]');
  assert(placeholder && placeholder.url === 'https://x.com/bob/status/30000000000000000003', 'Placeholder is not linked: ' + JSON.stringify(placeholder));
  assert(!doc.blocks.some(b => b.text.includes('Quoted words')), 'Captured quoted text'); f.close();
});
test('Embedded posts inside an X Article become a linked note', () => {
  const f = xArticle('Embedded post', `<p>${paragraph}</p><article data-testid="tweet"><a href="/bob/status/30000000000000000003"><time datetime="2026-10-19">Yesterday</time></a><div data-testid="tweetText">BOB_EMBEDDED_POST</div></article>`);
  const doc = f.capture();
  assert(!doc.blocks.some(b => b.text.includes('BOB_EMBEDDED_POST')), 'Captured the embedded post text');
  const note = doc.blocks.find(b => b.text === '[Quoted post — see original]');
  assert(note && note.url === 'https://x.com/bob/status/30000000000000000003', 'Embedded post is not linked: ' + JSON.stringify(note)); f.close();
});
test('Links inside a post keep their destinations', () => {
  const f = fixture(`<article data-testid="tweet"><div data-testid="User-Name">alice</div><a href="https://x.com/alice/status/${root}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText">See <a href="https://t.co/abc">example.com/research…</a> for <a href="/hashtag/x">#x</a> details</div></article>`, `https://x.com/alice/status/${root}`);
  const block = f.capture().blocks.find(b => b.kind === 'text' && b.text.startsWith('See'));
  assertRuns(block.runs, [{text: 'See '}, {text: 'example.com/research…', url: 'https://t.co/abc'}, {text: ' for '}, {text: '#x', url: 'https://x.com/hashtag/x'}, {text: ' details'}]); f.close();
  const plain = fixture(post('alice', root, 'Plain'), `https://x.com/alice/status/${root}`);
  assert(!('runs' in plain.capture().blocks.find(b => b.text === 'Plain')), 'Plain post gained runs'); plain.close();
});
test('Emoji images in a post keep their alt text in its text', () => {
  const emoji = '<img alt="❤️" src="https://abs-0.twimg.com/emoji/v2/svg/2764.svg">';
  const f = fixture(post('alice', root, `I ${emoji} this`), `https://x.com/alice/status/${root}`);
  const block = f.capture().blocks.find(b => b.kind === 'text');
  assert(block && block.text === 'I ❤️ this', 'Lost the emoji: ' + JSON.stringify(block)); f.close();
});
test('A post whose only content is an emoji image is still captured', () => {
  const emoji = '<img alt="❤️" src="https://abs-0.twimg.com/emoji/v2/svg/2764.svg">';
  const f = fixture(post('alice', root, emoji), `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(doc.blocks && doc.blocks.some(b => b.kind === 'text' && b.text === '❤️'), 'Dropped an emoji-only post: ' + JSON.stringify(doc)); f.close();
});
test('Line breaks in a post with a link survive', () => {
  const f = fixture(`<article data-testid="tweet"><a href="https://x.com/alice/status/${root}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText" style="white-space:pre-wrap">First line\n\nRead <a href="https://t.co/abc">this</a></div></article>`, `https://x.com/alice/status/${root}`);
  const block = f.capture().blocks.find(b => b.kind === 'text');
  assertRuns(block.runs, [{text: 'First line\n\nRead '}, {text: 'this', url: 'https://t.co/abc'}]); f.close();
});
test('A block inside a post with a link starts a new line, and plain text stays one run', () => {
  const f = fixture(`<article data-testid="tweet"><a href="https://x.com/alice/status/${root}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText"><span>Line one</span><div><span>Line </span><span>two </span><a href="https://t.co/abc">a.co</a></div></div></article>`, `https://x.com/alice/status/${root}`);
  const block = f.capture().blocks.find(b => b.kind === 'text');
  assertRuns(block.runs, [{text: 'Line one\nLine two '}, {text: 'a.co', url: 'https://t.co/abc'}]); f.close();
});
test('Text after a block inside a linked post starts its own line', () => {
  const f = fixture(`<article data-testid="tweet"><a href="https://x.com/alice/status/${root}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText">Before<div>Middle</div>After <a href="https://t.co/a">link</a></div></article>`, `https://x.com/alice/status/${root}`);
  const block = f.capture().blocks.find(b => b.kind === 'text');
  assertRuns(block.runs, [{text: 'Before\nMiddle\nAfter '}, {text: 'link', url: 'https://t.co/a'}]); f.close();
});
test('Unsafe links inside a post stay plain text', () => {
  const f = fixture(`<article data-testid="tweet"><div data-testid="User-Name">alice</div><a href="https://x.com/alice/status/${root}"><time datetime="2026-10-20">Today</time></a><div data-testid="tweetText">Try <a href="https://t.co/abc">this</a> or <a href="javascript:alert(1)">that</a></div></article>`, `https://x.com/alice/status/${root}`);
  const block = f.capture().blocks.find(b => b.kind === 'text' && b.text.startsWith('Try'));
  assertRuns(block.runs, [{text: 'Try '}, {text: 'this', url: 'https://t.co/abc'}, {text: ' or that'}]); f.close();
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
test('Anonymous X status pages capture an inline X Article instead of its empty post text', () => {
  const f = fixture(`<main><article><div dir="auto" class="whitespace-pre-wrap"></div><div><div data-article-part="cover"><img src="https://pbs.twimg.com/media/cover.jpg"></div><h1 data-article-part="title">What is a harness?</h1><div data-article-part="byline">6 min read</div><div data-article-part="body"><style>.x{}</style><div style="display:contents"><p>${paragraph}</p><h3>Humans use interfaces</h3><ul><li value="1">First point</li></ul></div></div></div><a href="https://m.x.com/alice/status/${root}?launch_app_store=true&ct=engagement_view_post">7:16 PM · Oct 22, 2026</a><div data-engagement-action="like">4.1K</div></article></main>`, `https://x.com/alice/status/${root}`);
  assert(f.summary().article === true, 'Did not recognize the inline X Article');
  const doc = f.capture();
  assert(doc.title === 'What is a harness?', 'Wrong title: ' + doc.title);
  assert(doc.blocks.find(b => b.kind === 'image')?.url === 'https://pbs.twimg.com/media/cover.jpg', 'Lost the Article cover');
  assert(doc.blocks.some(b => b.kind === 'heading' && b.text === 'Humans use interfaces'), 'Lost the article heading');
  assert(doc.blocks.some(b => b.kind === 'listItem' && b.text === 'First point'), 'Lost the article list');
  assert(!/6 min read|4\.1K|\.x\{\}/.test(JSON.stringify(doc.blocks)), 'Captured page chrome'); f.close();
});
test('X refuses a post with no readable text or images instead of saving only its timestamp', () => {
  const f = fixture(`<main><article><div dir="auto" class="whitespace-pre-wrap"></div><a href="https://m.x.com/alice/status/${root}?launch_app_store=true&ct=engagement_view_post">7:16 PM · Oct 22, 2026</a></article></main>`, `https://x.com/alice/status/${root}`);
  assert(f.summary().posts === 0, 'Counted an empty post as loaded');
  assert(!!f.capture().error, 'Saved a capture with no content'); f.close();
});

test('Inline text, emphasis, and links stay in one paragraph in DIV-based articles', () => {
  const f = xArticle('Rich text', `<p>${paragraph}</p><div>Read <strong>this important</strong> sentence with <a href="/reference">a reference</a> here.</div>`);
  const doc = f.capture();
  const block = doc.blocks.find(b => b.text === 'Read this important sentence with a reference here.');
  assert(!!block, 'Split one sentence into separate paragraphs');
  assert(block.runs.some(r => r.bold && r.text === 'this important'), 'Lost emphasis');
  assert(block.runs.some(r => r.url === 'https://x.com/reference'), 'Lost or failed to resolve the inline link');
  f.close();
});
test('Excluded descendants inside paragraphs never leak into saved text', () => {
  const f = xArticle('Clean article', `<p>${paragraph}<span aria-hidden="true">HIDDEN_CONTROL</span><button>AD_CONTROL</button><span hidden>HIDDEN_ELEMENT</span><span style="display:none">CSS_HIDDEN</span> Visible ending.</p>`);
  const text = JSON.stringify(f.capture().blocks);
  assert(!/HIDDEN_CONTROL|AD_CONTROL|HIDDEN_ELEMENT|CSS_HIDDEN/.test(text), 'Captured excluded paragraph descendants');
  assert(text.includes('Visible ending.'), 'Removed readable content'); f.close();
});
test('Numbered and nested lists, heading levels, quotes, and code indentation survive', () => {
  const f = xArticle('Structure', `<p>${paragraph}</p><h2>Section</h2><h3>Detail</h3><ol start="3"><li>First step<ul><li>Nested point</li></ul></li><li value="7">Second step</li></ol><blockquote>A useful quote.</blockquote><pre>  indented\n    further</pre>`);
  const blocks = f.capture().blocks;
  assert(blocks.some(b => b.kind === 'heading' && b.level === 3 && b.text === 'Detail'), 'Lost heading hierarchy');
  assert(blocks.some(b => b.kind === 'listItem' && b.marker === '3.' && b.text === 'First step'), 'Lost ordered list start');
  assert(blocks.some(b => b.kind === 'listItem' && b.marker === '7.'), 'Lost list value');
  assert(blocks.some(b => b.kind === 'listItem' && b.depth === 2 && b.text === 'Nested point'), 'Lost nested list');
  assert(blocks.some(b => b.kind === 'quote' && b.text === 'A useful quote.'), 'Lost quote');
  assert(blocks.some(b => b.kind === 'code' && b.text.startsWith('  indented')), 'Trimmed code indentation'); f.close();
});
test('An X Article body wrapped in its own article element is still captured', () => {
  const f = fixture(`<main><article data-testid="twitterArticleReadView"><div data-testid="twitter-article-title">Wrapped</div><article><div data-testid="twitterArticleRichTextView"><p>${paragraph}</p></div></article></article></main>`, `https://x.com/alice/status/${root}`);
  const doc = f.capture();
  assert(doc.blocks && doc.blocks.some(b => b.kind === 'text' && b.text === paragraph.trim()), 'Lost the wrapped body: ' + JSON.stringify(doc).slice(0, 300)); f.close();
});
test('An Article cover inside the body is saved once', () => {
  const f = fixture(`<main><article><div dir="auto" class="whitespace-pre-wrap"></div><h1 data-article-part="title">Cover inside</h1><div data-article-part="body"><div data-article-part="cover"><img width="400" height="200" src="https://pbs.twimg.com/media/cover.jpg"></div><p>${paragraph}</p></div></article></main>`, `https://x.com/alice/status/${root}`);
  const covers = f.capture().blocks.filter(b => b.kind === 'image' && b.url === 'https://pbs.twimg.com/media/cover.jpg');
  assert(covers.length === 1, 'Cover saved ' + covers.length + ' times'); f.close();
});
test('Emoji images inside an X Article paragraph do not split it', () => {
  const f = xArticle('Emoji article', `<p>${paragraph}I <img alt="❤️" src="https://abs-0.twimg.com/emoji/v2/svg/2764.svg"> this.</p>`);
  const blocks = f.capture().blocks.filter(b => b.text.endsWith('I ❤️ this.'));
  assert(blocks.length === 1, 'Split the paragraph around an emoji: ' + JSON.stringify(blocks)); f.close();
});
test('A list item with several paragraphs stays one item', () => {
  const f = xArticle('List paragraphs', `<p>${paragraph}</p><ol start="3"><li><p>Intro</p><p>Continuation</p></li><li>Next</li></ol>`);
  const items = f.capture().blocks.filter(b => b.kind === 'listItem');
  assert(items.length === 2 && items[0].marker === '3.' && items[1].marker === '4.', 'Wrong list items: ' + JSON.stringify(items));
  assert(items[0].text === 'Intro\n\nContinuation', 'Split one list item: ' + JSON.stringify(items[0].text)); f.close();
});
test('Inline code keeps its spaces', () => {
  const f = xArticle('Inline code', `<p>${paragraph}Set <code>x  =  1</code> first.</p>`);
  const runs = f.capture().blocks.flatMap(b => b.runs || []);
  assert(runs.some(r => r.code && r.text === 'x  =  1'), 'Collapsed spaces in inline code: ' + JSON.stringify(runs)); f.close();
});
test('Article title is emitted once rather than repeated as a body heading', () => {
  const f = xArticle('Unique title', `<h1>Unique title</h1><p>${paragraph}</p><h2>Another heading</h2>`);
  const doc = f.capture();
  assert(doc.title === 'Unique title', 'Wrong title');
  assert(!doc.blocks.some(b => b.kind === 'heading' && b.text === doc.title), 'Duplicated title'); f.close();
});
test('Active and credential-bearing inline links are kept as inert text', () => {
  const f = xArticle('Safe links', `<p>${paragraph}<a href="javascript:alert(1)">Unsafe JS</a> <a href="https://name:secret@example.org/">Private credentials</a></p>`);
  const doc = f.capture();
  assert(doc.blocks.some(b => b.text.includes('Unsafe JS')), 'Lost the visible link text');
  assert(!doc.blocks.flatMap(b => b.runs || []).some(r => r.url), 'Retained an unsafe link'); f.close();
});
test('Explicit line breaks stay inside their paragraph', () => {
  const f = xArticle('Line breaks', `<p>${paragraph}</p><div>Before<br>After</div>`);
  assert(f.capture().blocks.some(b => b.text === 'Before\nAfter'), 'Lost the explicit line break'); f.close();
});
test('Article images that have not loaded yet are kept', () => {
  const f = xArticle('Lazy image', `<p>${paragraph}</p><img src="https://pbs.twimg.com/media/lazy.jpg" alt="A chart" loading="lazy" style="width:300px;height:200px"><img src="https://abs.twimg.com/icon.png" style="width:20px;height:20px">`);
  const images = f.capture().blocks.filter(b => b.kind === 'image');
  assert(images.length === 1 && images[0].url.endsWith('lazy.jpg'), 'Wrong images: ' + JSON.stringify(images)); f.close();
});
test('Styled spans retain emphasis without copying source styles or inventing anchor links', () => {
  const f = xArticle('Styled spans', `<p>${paragraph}</p><div>A <span style="font-weight:700">bold</span> and <span style="font-style:italic">italic</span> sentence with <a name="section">an anchor</a>.</div>`);
  const block = f.capture().blocks.find(b => b.text === 'A bold and italic sentence with an anchor.');
  assert(block && block.runs.some(r => r.bold && r.text === 'bold'), 'Lost CSS-based emphasis');
  assert(block.runs.some(r => r.italic && r.text === 'italic'), 'Lost CSS-based italics');
  assert(!block.runs.some(r => r.url), 'Invented a URL for a named anchor'); f.close();
});
document.getElementById('results').textContent = results.join('\n');
document.title = 'Xave capture: ' + results.filter(x => x.startsWith('PASS')).length + '/' + results.length + ' passed' + (results.some(x => x.startsWith('FAIL')) ? ' FAILED' : '');
document.getElementById('summary').textContent = results.filter(x => x.startsWith('PASS')).length + '/' + results.length + ' passed';
