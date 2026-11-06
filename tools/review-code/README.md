# Nilak review login code (Firebase)

A password-protected page that shows the newest Telegram login code of the Google Play review account.
See `functions/main.py` for how it works. No secrets are in this folder; they live in Google Secret Manager.

## Deploy (once)

1. Firebase console → your Nilak project → upgrade to the **Blaze** plan (card required, usage stays in the free tier).
2. On your computer, with Node.js installed:

```bash
npm install -g firebase-tools
firebase login
cd tools/review-code
firebase use --add            # pick the Nilak project
firebase functions:secrets:set REVIEW_SESSION
firebase functions:secrets:set TG_API_ID
firebase functions:secrets:set TG_API_HASH
firebase functions:secrets:set REVIEW_PAGE_PASSWORD
firebase deploy --only functions
```

The deploy prints the page link (`https://review-code-....run.app` or `https://europe-west1-<project>.cloudfunctions.net/review_code`).

## Play Console → App content → App access

- Phone: the review number
- Login code: "Tap Next in Nilak, then open <link>, enter password <password>, press Show latest code."
- Two-step password: if set on the account

To stop it: end the session in Telegram → Settings → Devices, or delete the function in the Firebase console.
