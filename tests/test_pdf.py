from app.pdf import html_to_pdf


def test_html_to_pdf_launches_chromium_and_returns_pdf_bytes():
    pdf = html_to_pdf("<html><body><h1>xave PDF integration test</h1></body></html>")

    assert pdf.startswith(b"%PDF-")
    assert len(pdf) > 1_000
