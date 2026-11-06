"""
Nilak review login code page (Firebase Cloud Function, Python).

Google Play reviewers sign in to Nilak with the review account. Telegram sends that account's login code
as a message from "Telegram" (user 777000) into its own chats, not by SMS. This page, protected by a
password, reads the newest code from that chat with a saved session and shows it, so the reviewer can type
it into Nilak. Nothing is stored; secrets live only in Google Secret Manager:

  REVIEW_SESSION        Telethon StringSession of the review account (a full key to that account)
  TG_API_ID, TG_API_HASH  the API ID/hash the session was created with (the review account's own)
  REVIEW_PAGE_PASSWORD  the password given to reviewers in Play Console
"""
import asyncio
import hmac
import html
import re
import time

from firebase_functions import https_fn
from firebase_functions.params import SecretParam
from telethon import TelegramClient
from telethon.sessions import StringSession

SESSION = SecretParam("REVIEW_SESSION")
API_ID = SecretParam("TG_API_ID")
API_HASH = SecretParam("TG_API_HASH")
PAGE_PASSWORD = SecretParam("REVIEW_PAGE_PASSWORD")

TELEGRAM_SERVICE = 777000   # the "Telegram" chat that receives login codes
MAX_AGE_SECONDS = 600       # codes expire after a few minutes; older ones are not shown


async def latest_code():
    """The newest login code (5 or 6 digits) sent to the account in the last 10 minutes, with its age."""
    async with TelegramClient(StringSession(SESSION.value), int(API_ID.value), API_HASH.value) as client:
        async for message in client.iter_messages(TELEGRAM_SERVICE, limit=5):
            age = time.time() - message.date.timestamp()
            if age > MAX_AGE_SECONDS:
                break
            found = re.search(r"\b(\d{5,6})\b", message.message or "")
            if found:
                return found.group(1), int(age)
    return None, None


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Nilak review login code</title></head>
<body style="font-family:system-ui,sans-serif;max-width:440px;margin:40px auto;padding:0 16px;color:#1a1523">
<h2 style="margin-bottom:4px">Nilak review login code</h2>
<p style="color:#615b6e;margin-top:0">1. In Nilak, enter the review phone number and tap <b>Next</b>.<br>
2. Enter the page password below and press <b>Show latest code</b>.<br>
3. Type the code into Nilak.</p>
{body}
<form method="post">
<input name="p" type="password" placeholder="Page password" autocomplete="current-password"
 style="padding:12px;width:100%;box-sizing:border-box;font-size:16px;border:1px solid #ccc;border-radius:10px">
<button style="margin-top:10px;padding:12px 18px;font-size:16px;border:0;border-radius:10px;background:#7138d5;color:#fff">
Show latest code</button>
</form>
</body></html>"""


@https_fn.on_request(
    secrets=[SESSION, API_ID, API_HASH, PAGE_PASSWORD],
    region="europe-west1",
    memory=256,
    timeout_sec=30,
    max_instances=2,
)
def review_code(req: https_fn.Request) -> https_fn.Response:
    body = ""
    if req.method == "POST":
        if not hmac.compare_digest(req.form.get("p", ""), PAGE_PASSWORD.value):
            body = "<p style='color:#c00'>Wrong password.</p>"
        else:
            try:
                code, age = asyncio.run(latest_code())
            except Exception as error:  # session ended, network, Telegram error
                code, age = None, None
                body = f"<p style='color:#c00'>Could not read the code ({html.escape(type(error).__name__)}). Try again in a minute.</p>"
            if code:
                body = (f"<p style='font-size:44px;letter-spacing:8px;margin:16px 0'><b>{html.escape(code)}</b></p>"
                        f"<p style='color:#615b6e'>Sent {age} seconds ago.</p>")
            elif not body:
                body = "<p>No new code yet. Tap <b>Next</b> in Nilak first, wait a few seconds, then press the button again.</p>"
    response = https_fn.Response(PAGE.format(body=body), mimetype="text/html")
    response.headers["Cache-Control"] = "no-store"
    return response
