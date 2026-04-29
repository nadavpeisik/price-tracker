from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException
from playwright.async_api import async_playwright, Browser, Playwright
from pydantic import BaseModel

playwright_instance: Playwright = None
browser: Browser = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global playwright_instance, browser
    playwright_instance = await async_playwright().start()
    browser = await playwright_instance.chromium.launch(
        headless=True,
        args=["--no-sandbox", "--disable-dev-shm-usage"],
    )
    yield
    await browser.close()
    await playwright_instance.stop()


app = FastAPI(lifespan=lifespan)


class ScrapeRequest(BaseModel):
    url: str


class ScrapeResponse(BaseModel):
    innerText: str


@app.post("/scrape", response_model=ScrapeResponse)
async def scrape(request: ScrapeRequest):
    context = await browser.new_context()
    try:
        page = await context.new_page()
        await page.goto(request.url, wait_until="domcontentloaded", timeout=30000)
        inner_text = await page.inner_text("body")
        return ScrapeResponse(innerText=inner_text)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        await context.close()


@app.get("/health")
async def health():
    return {"status": "ok"}
