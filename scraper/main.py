from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from playwright.async_api import async_playwright

app = FastAPI()


class ScrapeRequest(BaseModel):
    url: str


class ScrapeResponse(BaseModel):
    innerText: str


@app.post("/scrape", response_model=ScrapeResponse)
async def scrape(request: ScrapeRequest):
    try:
        async with async_playwright() as p:
            browser = await p.chromium.launch(
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage"],
            )
            page = await browser.new_page()
            await page.goto(request.url, wait_until="domcontentloaded", timeout=30000)
            inner_text = await page.inner_text("body")
            await browser.close()
            return ScrapeResponse(innerText=inner_text)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    return {"status": "ok"}
