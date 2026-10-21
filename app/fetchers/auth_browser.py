"""Auth-browser fallback: render the page with your persisted X login and pull
structured data out of the GraphQL responses the page makes.

This is the part most sensitive to X changing its internals. To stay resilient
we do NOT hard-code GraphQL paths: we deep-search every captured JSON response
for tweet-shaped objects, then reconstruct the author's self-reply chain from
`in_reply_to_status_id_str`. A DOM scrape is the last-resort fallback. Captured
operation names are logged so the parser can be adapted when X rotates them.
"""
from __future__ import annotations

import logging
import re
from datetime import date
from typing import Optional

from playwright.sync_api import sync_playwright

from .. import config
from .base import (
    Author,
    DividerBlock,
    Document,
    FetchError,
    ImageBlock,
    PlaceholderBlock,
    TextBlock,
)
from .syndication import _best_video_url

log = logging.getLogger("xave.auth_browser")


def _scroll_until_stable(
    page,
    captured: list[dict],
    *,
    focal_id: Optional[str] = None,
    max_scrolls: int = 40,
    stable_rounds: int = 3,
) -> None:
    """Scroll until the connected thread or finite Article page stops changing."""

    def snapshot():
        if focal_id:
            signature = _captured_thread_signature(captured, focal_id)
            if signature:
                return ("thread", signature)
        return ("page", page.evaluate("document.body.scrollHeight"), len(captured))

    previous = snapshot()
    stable = 0
    for _ in range(max_scrolls):
        page.mouse.wheel(0, 3200)
        page.wait_for_timeout(700)
        current = snapshot()
        if current == previous:
            stable += 1
            if stable >= stable_rounds:
                return
        else:
            stable = 0
        previous = current
    raise FetchError(
        "X kept loading additional content, so xave could not verify that the "
        "thread or Article was complete."
    )


def _captured_thread_signature(captured: list[dict], focal_id: str) -> tuple[str, ...]:
    tweets: dict[str, dict] = {}
    for payload in captured:
        _collect_tweets(payload, tweets)
    focal = tweets.get(focal_id)
    if not focal:
        return ()
    return tuple(
        _rest_id(tweet) or ""
        for tweet in _self_reply_chain(tweets, focal_id, _user_id(focal))
    )


class AuthBrowserFetcher:
    def fetch(self, parsed) -> Document:
        if not config.auth_available():
            raise FetchError("No X login is configured. Run `python -m app.auth.login` first.")

        url = parsed.original_url if parsed.doc_type == "article" else parsed.canonical_url
        captured: list[dict] = []

        with sync_playwright() as pw:
            browser = pw.chromium.launch(args=["--disable-blink-features=AutomationControlled"])
            try:
                context = browser.new_context(
                    storage_state=str(config.STORAGE_STATE),
                    user_agent=config.USER_AGENT,
                    viewport={"width": 1280, "height": 1800},
                    locale="en-US",
                )
                context.add_init_script(
                    "Object.defineProperty(navigator,'webdriver',{get:()=>undefined});"
                )
                page = context.new_page()

                def on_response(resp) -> None:
                    try:
                        if "/graphql/" not in resp.url:
                            return
                        if "application/json" not in (resp.headers.get("content-type", "")):
                            return
                        log.info("graphql op: %s", resp.url.split("/graphql/")[-1].split("?")[0])
                        captured.append(resp.json())
                    except Exception:
                        pass

                page.on("response", on_response)
                page.goto(url, wait_until="domcontentloaded", timeout=config.NAV_TIMEOUT_MS)

                try:
                    page.wait_for_selector('article[data-testid="tweet"]', timeout=8000)
                except Exception:
                    pass
                _scroll_until_stable(
                    page,
                    captured,
                    focal_id=parsed.tweet_id if parsed.doc_type != "article" else None,
                )

                dom_html = page.content()
                if _looks_logged_out(dom_html):
                    raise FetchError(
                        "Your saved X session looks logged out or expired. "
                        "Re-run `python -m app.auth.login`."
                    )
            finally:
                browser.close()

        if parsed.doc_type == "article" or _linked_article_candidate(
            captured, parsed.tweet_id
        ):
            return self._build_article(parsed, captured, dom_html)
        return self._build_thread(parsed, captured, dom_html)

    # --- threads -----------------------------------------------------------
    def _build_thread(self, parsed, captured: list[dict], dom_html: str = "") -> Document:
        tweets: dict[str, dict] = {}
        for payload in captured:
            _collect_tweets(payload, tweets)

        focal = parsed.tweet_id
        focal_node = tweets.get(focal)
        chain = _self_reply_chain(tweets, focal, _user_id(focal_node or {})) if focal_node else []
        if not chain:
            dom_document = _thread_from_dom(parsed, dom_html)
            if dom_document:
                return dom_document
            if tweets and not focal_node:
                raise FetchError(
                    "X returned conversation data without the requested tweet; "
                    "xave refused to substitute a different post."
                )
            raise FetchError(
                "Could not extract a verified thread from X. "
                "The free path may still work for a single public tweet."
            )

        root = chain[0]
        blocks: list = []
        for index, node in enumerate(chain):
            if index > 0:
                blocks.append(DividerBlock())
            blocks.extend(_gql_tweet_to_blocks(node))

        return Document(
            source_url=parsed.canonical_url,
            retrieved_at=date.today().isoformat(),
            doc_type="thread" if len(chain) > 1 else "tweet",
            author=_author_from_node(root),
            blocks=blocks,
            root_id=_rest_id(root) or parsed.tweet_id,
        )

    # --- articles ----------------------------------------------------------
    def _build_article(self, parsed, captured: list[dict], dom_html: str) -> Document:
        if parsed.doc_type == "article":
            title, author, blocks = _article_from_graphql(
                captured, article_id=parsed.tweet_id
            )
        else:
            title, author, blocks = _article_from_graphql(
                captured, status_id=parsed.tweet_id
            )
        if not blocks:  # best-effort DOM fallback
            title, author, blocks = _article_from_dom(dom_html, title, author)
        if not blocks:
            raise FetchError(
                "Could not extract this Article's content. X's Article format may have changed; "
                "the GraphQL/DOM extraction needs updating."
            )
        return Document(
            source_url=parsed.canonical_url,
            retrieved_at=date.today().isoformat(),
            doc_type="article",
            author=author or Author(name=parsed.user or "", handle=parsed.user or ""),
            title=title,
            blocks=blocks,
            root_id=parsed.tweet_id,
        )


# --- generic GraphQL tweet extraction -------------------------------------
def _collect_tweets(node, out: dict) -> None:
    """Deep-search any JSON for tweet-shaped objects (legacy.full_text + core)."""
    if isinstance(node, dict):
        legacy = node.get("legacy")
        if isinstance(legacy, dict) and "full_text" in legacy and "core" in node:
            tid = _rest_id(node) or legacy.get("id_str")
            if tid:
                out[str(tid)] = node
        inner = node.get("tweet") if node.get("__typename") == "TweetWithVisibilityResults" else None
        if isinstance(inner, dict):
            _collect_tweets(inner, out)
        for value in node.values():
            _collect_tweets(value, out)
    elif isinstance(node, list):
        for value in node:
            _collect_tweets(value, out)


def _self_reply_chain(tweets: dict, focal: str, author_id) -> list[dict]:
    same = {tid: t for tid, t in tweets.items() if _user_id(t) == author_id}
    if focal not in same:
        return []

    # Walk up to the root of the self-chain.
    root = focal
    for _ in range(200):
        parent = _legacy(same.get(root, {})).get("in_reply_to_status_id_str")
        if parent and parent in same:
            root = parent
        else:
            break

    children: dict[str, list[str]] = {}
    for tid, node in same.items():
        parent = _legacy(node).get("in_reply_to_status_id_str")
        children.setdefault(parent, []).append(tid)

    ordered: list[str] = []
    seen: set[str] = set()

    def visit(tweet_id: str) -> None:
        if tweet_id in seen:
            return
        seen.add(tweet_id)
        ordered.append(tweet_id)
        for child in sorted(children.get(tweet_id, []), key=lambda item: int(item)):
            visit(child)

    visit(root)
    return [same[tweet_id] for tweet_id in ordered]


def _thread_from_dom(parsed, dom_html: str) -> Optional[Document]:
    """Conservative DOM fallback scoped to the contiguous author run containing the focal tweet."""
    if not dom_html:
        return None

    from bs4 import BeautifulSoup

    soup = BeautifulSoup(dom_html, "html.parser")
    articles = soup.select('article[data-testid="tweet"]')
    identities = [_dom_tweet_identity(article) for article in articles]
    focal_index = next(
        (index for index, identity in enumerate(identities) if identity and identity[1] == parsed.tweet_id),
        None,
    )
    if focal_index is None:
        return None

    handle = identities[focal_index][0]
    start = focal_index
    while start > 0 and identities[start - 1] and identities[start - 1][0].lower() == handle.lower():
        start -= 1
    end = focal_index
    while (
        end + 1 < len(articles)
        and identities[end + 1]
        and identities[end + 1][0].lower() == handle.lower()
    ):
        end += 1

    selected = list(zip(articles[start : end + 1], identities[start : end + 1]))
    if not selected:
        return None

    focal_article = articles[focal_index]
    user_name = focal_article.select_one('[data-testid="User-Name"]')
    raw_name = user_name.get_text(" ", strip=True) if user_name else handle
    name = raw_name.split(f"@{handle}", 1)[0].strip() or handle
    avatar = next(
        (
            image.get("src")
            for image in focal_article.select("img[src]")
            if "/profile_images/" in (image.get("src") or "")
        ),
        None,
    )

    blocks: list = []
    root_id = parsed.tweet_id
    for index, (article, identity) in enumerate(selected):
        if not identity:
            continue
        if index > 0:
            blocks.append(DividerBlock())
        _, tweet_id = identity
        if not root_id or index == 0:
            root_id = tweet_id
        text = article.select_one('[data-testid="tweetText"]')
        if text:
            cleaned = text.get_text("\n", strip=True)
            if cleaned:
                blocks.append(TextBlock(text=cleaned))
        for image in article.select("img[src]"):
            src = image.get("src") or ""
            if "/media/" in src:
                blocks.append(ImageBlock(url=src, alt=image.get("alt") or ""))
        if article.select_one("video"):
            blocks.append(
                PlaceholderBlock(
                    media_kind="video",
                    label="Video",
                    link=f"https://x.com/{handle}/status/{tweet_id}",
                )
            )

    if not any(isinstance(block, TextBlock) for block in blocks):
        return None
    return Document(
        source_url=parsed.canonical_url,
        retrieved_at=date.today().isoformat(),
        doc_type="thread" if len(selected) > 1 else "tweet",
        author=Author(name=name, handle=handle, avatar_url=avatar),
        blocks=blocks,
        root_id=selected[0][1][1],
    )


def _dom_tweet_identity(article) -> Optional[tuple[str, str]]:
    for link in article.select("a[href]"):
        match = re.search(r"/([^/]+)/status(?:es)?/(\d+)", link.get("href") or "")
        if match:
            return match.group(1), match.group(2)
    return None


# --- GraphQL node accessors (defensive across X variations) ----------------
def _rest_id(node: dict) -> Optional[str]:
    rid = (node or {}).get("rest_id")
    return str(rid) if rid else None


def _legacy(node: dict) -> dict:
    return (node or {}).get("legacy", {}) or {}


def _user_result(node: dict) -> dict:
    return (((node or {}).get("core", {}) or {}).get("user_results", {}) or {}).get("result", {}) or {}


def _user_id(node: dict):
    res = _user_result(node)
    return res.get("rest_id") or _legacy(node).get("user_id_str")


def _user_field(node: dict, key: str):
    res = _user_result(node)
    legacy = res.get("legacy", {}) or {}
    core = res.get("core", {}) or {}
    return legacy.get(key) or core.get(key)


def _author_handle(node: dict) -> str:
    return _user_field(node, "screen_name") or ""


def _author_from_node(node: dict) -> Author:
    res = _user_result(node)
    avatar = (
        (res.get("avatar", {}) or {}).get("image_url")
        or (res.get("legacy", {}) or {}).get("profile_image_url_https")
    )
    if avatar:
        avatar = avatar.replace("_normal.", "_400x400.")
    return Author(
        name=_user_field(node, "name") or "",
        handle=_user_field(node, "screen_name") or "",
        avatar_url=avatar,
    )


def _gql_clean_text(legacy: dict) -> str:
    text = legacy.get("full_text", "") or ""
    entities = legacy.get("entities", {}) or {}
    for media in (legacy.get("extended_entities", {}) or {}).get("media", []) or entities.get("media", []) or []:
        if media.get("url"):
            text = text.replace(media["url"], "")
    for link in entities.get("urls", []) or []:
        if link.get("url"):
            text = text.replace(link["url"], link.get("display_url") or link.get("expanded_url") or "")
    return text.strip()


def _gql_tweet_to_blocks(node: dict) -> list:
    legacy = _legacy(node)
    handle = _author_handle(node)
    permalink = f"https://x.com/{handle}/status/{_rest_id(node) or ''}"
    blocks: list = []
    text = _gql_clean_text(legacy)
    if text:
        blocks.append(TextBlock(text=text))

    media = (legacy.get("extended_entities", {}) or {}).get("media") or (
        legacy.get("entities", {}) or {}
    ).get("media") or []
    for item in media:
        mtype = item.get("type")
        if mtype == "photo":
            blocks.append(ImageBlock(url=item.get("media_url_https", ""), alt=item.get("ext_alt_text") or ""))
        elif mtype in ("video", "animated_gif"):
            label = "GIF" if mtype == "animated_gif" else "Video"
            link = _best_video_url(item) or item.get("expanded_url") or permalink
            blocks.append(PlaceholderBlock(media_kind="video", label=label, link=link))

    quoted = node.get("quoted_status_result", {}) or {}
    qresult = quoted.get("result")
    if qresult:
        qnode = qresult.get("tweet", qresult)
        qhandle = _author_handle(qnode)
        snippet = _gql_clean_text(_legacy(qnode))[:140]
        link = f"https://x.com/{qhandle}/status/{_rest_id(qnode) or ''}"
        blocks.append(
            PlaceholderBlock(
                media_kind="quote",
                label=f"Quoted @{qhandle}: {snippet}" if snippet else f"Quoted @{qhandle}",
                link=link,
            )
        )
    return blocks


# --- article extraction ----------------------------------------------------
def _linked_article_candidate(captured: list[dict], status_id: Optional[str]) -> Optional[dict]:
    """Return the Article explicitly linked from the requested status."""
    if not status_id:
        return None

    candidates: list[dict] = []

    def walk(node) -> None:
        if isinstance(node, dict):
            if _rest_id(node) == str(status_id):
                article_field = node.get("article")
                article_results = (
                    article_field.get("article_results")
                    if isinstance(article_field, dict)
                    else None
                )
                article = (
                    article_results.get("result")
                    if isinstance(article_results, dict)
                    else None
                )
                if isinstance(article, dict):
                    candidates.append(article)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    for payload in captured:
        walk(payload)

    unique: dict[tuple[str, ...], dict] = {}
    for candidate in candidates:
        key = tuple(sorted(_article_candidate_ids(candidate)))
        if not key:
            key = (repr(candidate),)
        unique.setdefault(key, candidate)
    return next(iter(unique.values())) if len(unique) == 1 else None


def _article_from_graphql(
    captured: list[dict],
    article_id: Optional[str] = None,
    *,
    status_id: Optional[str] = None,
):
    """Find the requested Article without substituting an unrelated candidate."""
    if status_id:
        article = _linked_article_candidate(captured, status_id)
        if not article:
            return None, None, []
        return _article_to_content(article)

    candidates: list[dict] = []

    def walk(node):
        if isinstance(node, dict):
            if "title" in node and (
                "content_state" in node or "preview_text" in node or "article_results" in node
            ):
                candidates.append(node)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    for payload in captured:
        walk(payload)

    exact = [
        candidate
        for candidate in candidates
        if article_id and article_id in _article_candidate_ids(candidate)
    ]
    if len(exact) == 1:
        article = exact[0]
    elif (
        not exact
        and len(candidates) == 1
        and not _article_candidate_ids(candidates[0])
    ):
        article = candidates[0]
    else:
        return None, None, []

    return _article_to_content(article)


def _article_to_content(article: dict):
    title = article.get("title")
    content = article.get("content_state") or {}
    if isinstance(content, str):
        import json

        try:
            content = json.loads(content)
        except Exception:
            content = {}
    if not isinstance(content, dict):
        content = {}

    blocks: list = []
    entity_map = _normalize_entity_map(
        content.get("entityMap") or content.get("entity_map") or {}
    )
    seen_images: set[str] = set()
    for blk in content.get("blocks") or []:
        text = (blk.get("text") or "").strip()
        btype = blk.get("type", "unstyled")
        images = []
        for entity_range in blk.get("entityRanges") or blk.get("entity_ranges") or []:
            key = entity_range.get("key")
            entity = entity_map.get(str(key), entity_map.get(key, {}))
            image = _image_from_entity(entity)
            if image and image.url not in seen_images:
                images.append(image)
                seen_images.add(image.url)

        if btype == "atomic":
            blocks.extend(images)
        elif text and btype.startswith("header"):
            level = 3 if btype.endswith("three") or btype.endswith("four") else 2
            blocks.append(_heading(level, text))
            blocks.extend(images)
        elif text:
            blocks.append(TextBlock(text=text))
            blocks.extend(images)

    return title, _author_from_article(article), blocks


def _normalize_entity_map(raw_entity_map) -> dict[str, dict]:
    """Normalize both DraftJS object maps and X's current key/value list form."""
    if isinstance(raw_entity_map, dict):
        return {
            str(key): value
            for key, value in raw_entity_map.items()
            if isinstance(value, dict)
        }
    if not isinstance(raw_entity_map, list):
        return {}

    normalized: dict[str, dict] = {}
    for entry in raw_entity_map:
        if not isinstance(entry, dict) or entry.get("key") is None:
            continue
        value = entry.get("value")
        if not isinstance(value, dict):
            continue
        normalized[str(entry["key"])] = value
    return normalized


def _article_from_dom(dom_html: str, title: Optional[str], author: Optional[Author]):
    from bs4 import BeautifulSoup

    soup = BeautifulSoup(dom_html, "html.parser")
    if not title:
        og = soup.find("meta", property="og:title")
        title = (og.get("content") if og else None) or (soup.title.string if soup.title else None)

    container = (
        soup.select_one('[data-testid="twitterArticleReadView"]')
        or soup.select_one('article[data-testid="article"]')
        or soup.select_one('[data-testid="article"]')
    )
    if not author and container:
        author = _article_author_from_dom(soup, container)

    blocks: list = []
    if container:
        seen_images: set[str] = set()
        for el in container.find_all(["h1", "h2", "h3", "p", "img"]):
            if el.name == "img":
                src = el.get("src") or ""
                if _is_content_image_url(src) and src not in seen_images:
                    blocks.append(ImageBlock(url=src, alt=el.get("alt") or ""))
                    seen_images.add(src)
                continue
            text = el.get_text(" ", strip=True)
            if len(text) < 2:
                continue
            if el.name in ("h2", "h3"):
                blocks.append(_heading(2 if el.name == "h2" else 3, text))
            elif el.name == "h1" and not title:
                title = text
            else:
                blocks.append(TextBlock(text=text))
    # De-duplicate consecutive identical paragraphs (X renders some text twice).
    deduped: list = []
    for blk in blocks:
        if deduped and getattr(blk, "text", None) and getattr(deduped[-1], "text", None) == blk.text:
            continue
        deduped.append(blk)
    return title, author, deduped


def _article_candidate_ids(article: dict) -> set[str]:
    ids = {
        str(article[key])
        for key in ("rest_id", "id", "id_str", "article_id")
        if article.get(key) is not None
    }
    metadata = article.get("metadata")
    if isinstance(metadata, dict):
        ids.update(
            str(metadata[key])
            for key in ("rest_id", "id", "id_str", "article_id")
            if metadata.get(key) is not None
        )
    return ids


def _author_from_article(article: dict) -> Optional[Author]:
    found: Optional[dict] = None

    def walk(node) -> None:
        nonlocal found
        if found is not None:
            return
        if isinstance(node, dict):
            if node.get("screen_name") and (node.get("name") or node.get("screen_name")):
                found = node
                return
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(article.get("author") or article.get("author_results") or article)
    if not found:
        return None
    avatar = (
        found.get("profile_image_url_https")
        or found.get("image_url")
        or (found.get("avatar") or {}).get("image_url")
    )
    if avatar:
        avatar = avatar.replace("_normal.", "_400x400.")
    return Author(
        name=found.get("name") or found.get("screen_name"),
        handle=found.get("screen_name"),
        avatar_url=avatar,
    )


def _image_from_entity(entity: dict) -> Optional[ImageBlock]:
    if not isinstance(entity, dict):
        return None
    kind = str(entity.get("type") or entity.get("__typename") or "").lower()
    data = entity.get("data") if isinstance(entity.get("data"), dict) else entity
    urls: list[str] = []
    alt = ""

    def walk(node) -> None:
        nonlocal alt
        if isinstance(node, dict):
            for key, value in node.items():
                lowered = key.lower()
                if lowered in {"alt", "alt_text", "ext_alt_text"} and isinstance(value, str):
                    alt = alt or value
                if lowered in {
                    "url",
                    "image_url",
                    "media_url",
                    "media_url_https",
                    "original_img_url",
                } and isinstance(value, str):
                    urls.append(value)
                else:
                    walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(data)
    url = next((candidate for candidate in urls if _is_content_image_url(candidate)), None)
    if not url and kind not in {"image", "photo", "media"}:
        return None
    return ImageBlock(url=url, alt=alt) if url else None


def _is_content_image_url(url: str) -> bool:
    lowered = (url or "").lower()
    if not lowered or "/profile_images/" in lowered or "/emoji/" in lowered:
        return False
    return (
        "/media/" in lowered
        or lowered.startswith("data:image/")
        or lowered.split("?", 1)[0].endswith((".png", ".jpg", ".jpeg", ".webp", ".gif"))
    )


def _article_author_from_dom(soup, container) -> Optional[Author]:
    creator = soup.find("meta", attrs={"name": "twitter:creator"})
    handle = (creator.get("content") if creator else "") or ""
    handle = handle.strip().lstrip("@")

    user_name = container.select_one('[data-testid="User-Name"]')
    raw_name = user_name.get_text(" ", strip=True) if user_name else ""
    if not handle and user_name:
        link = user_name.select_one("a[href]")
        match = re.match(r"^/([^/]+)", link.get("href") or "") if link else None
        if match and match.group(1) != "i":
            handle = match.group(1)

    author_meta = soup.find("meta", attrs={"name": "author"})
    name = (author_meta.get("content") if author_meta else "") or ""
    if not name and raw_name:
        name = raw_name.split(f"@{handle}", 1)[0].strip() if handle else raw_name
    if not handle and not name:
        return None

    avatar = next(
        (
            image.get("src")
            for image in container.select("img[src]")
            if "/profile_images/" in (image.get("src") or "")
        ),
        None,
    )
    return Author(name=name or handle, handle=handle, avatar_url=avatar)


def _heading(level: int, text: str):
    from .base import HeadingBlock

    return HeadingBlock(level=level, text=text)


def _looks_logged_out(dom_html: str) -> bool:
    markers = ('href="/login"', "Sign in to X", "data-testid=\"loginButton\"", "Log in to Twitter")
    hits = sum(1 for m in markers if m in dom_html)
    return hits >= 1 and 'data-testid="tweet"' not in dom_html
