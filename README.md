# Kharcha — Android expense tracker for UPI payments

Kharcha logs your payments by itself and gives you weekly and monthly reports. Everything stays on the phone.

## How it captures payments

| Method | How it works | Needs |
|---|---|---|
| **₹ bubble** | A floating bubble sits on top of all apps. On a GPay / PhonePe / Paytm success screen, tap it. Kharcha reads the screen text (amount, payee, UPI ref) and shows a card where you confirm the category. | Accessibility service |
| **UPI notifications** | Payment notifications from GPay, PhonePe, Paytm, BHIM, CRED, Amazon Pay and bank apps are logged automatically. | Notification access |
| **Bank SMS** | Debit SMS from HDFC, SBI, ICICI, Axis, Kotak and others are logged automatically. Credits, OTPs and reminders are ignored. | SMS permission |

When the same payment shows up more than once (SMS + notification + bubble), Kharcha recognises it by its UPI ref, or by matching the amount and time, and merges the copies.

**Categories:** Kharcha guesses the category from the payee (Swiggy → Food, Uber → Travel…). Auto-logged payments post a quiet notification you can tap to change the category. Once you change a payee's category, Kharcha remembers it for next time.

**Reports:** The home screen has Week and Month views. Each shows the total, the change from the previous period, a daily chart, a category breakdown, top payees and every transaction. When a week (Mon–Sun) or a month ends, a summary notification arrives. Export everything as a CSV from the ⋮ menu.

## Install

1. Open **Releases → latest** in this repo on your phone and download `Kharcha.apk`. Every push to `main` builds a new one.
2. Open the APK and allow "Install unknown apps" for your browser or file manager when Android asks.
3. Open Kharcha and tap **Enable** on each item in *Finish setup*.

### "Restricted setting" when you turn on the bubble
For sideloaded apps, Android 13+ blocks accessibility and notification access until you allow it:
**Settings → Apps → Kharcha → ⋮ (top right) → Allow restricted settings**, then enable it again.

## Build locally
Open the project in Android Studio (JDK 17) and run, or run `gradle :app:assembleDebug`.

The repo includes a fixed signing key (`app/kharcha.keystore`), so each new APK installs over the old one and your data is kept.
