# Expensify — Android expense tracker for UPI and card payments

Expensify logs your payments by itself and gives you daily, weekly and monthly reports. Everything stays on the phone: the app has no internet permission.

## How it captures payments

| Method | How it works | Needs |
|---|---|---|
| **₹ bubble** | A floating bubble sits on top of all apps. Tap it on a payment screen: a GPay / PhonePe / Paytm success screen, or a Swiggy / Zomato / Blinkit / Zepto order or checkout page. Expensify reads the amount, payee, UPI ref and the time shown on screen, and shows a card where you confirm the category. If the app exposes no text, the bubble reads a screenshot with on-device text recognition (the image is never saved). This also covers card payments that send you no SMS, such as when someone else's card paid. | Accessibility service |
| **UPI notifications** | Payment notifications from GPay, PhonePe, Paytm, BHIM, CRED, Amazon Pay and bank apps are logged automatically. | Notification access |
| **Bank SMS** | Debit SMS and credit card spend alerts from HDFC, SBI, ICICI, Axis, IDFC, Kotak, Amex and others are logged automatically. Credits, refunds, OTPs, reminders and credit card bill payments are ignored (the card spends are already counted). | SMS permission |

When the same payment shows up more than once (SMS + notification + bubble, or two SMS from the bank for one charge), Expensify recognises it by its UPI ref, or by matching the amount and time, and merges the copies. Two separate bank SMS for the same amount are kept as two payments.

**Categories:** Expensify guesses the category from the payee (Swiggy → Food, Uber → Travel…). Auto-logged payments post a quiet notification that opens the transaction. Once you change a payee's category, Expensify remembers it for next time.

**Reports:** The home screen shows today's spend and payments, then Week and Month views (swipe sideways to move between periods): the total, the change from the previous period, a daily chart, a category breakdown, top payees and every transaction. Tap a transaction for its full details. When a week (Mon–Sun) or a month ends, a summary notification arrives. Export everything as a CSV from the ☰ menu.

**Lend & borrow:** Record money you lent or borrowed, mark a payment as lent, and settle up per person.

**Quick add:** The + button (bottom right), long-press the app icon, or the home screen widgets for today's and this week's spend.

## Install

1. Open **Releases → latest** in this repo on your phone and download `Expensify.apk`. Every push to `main` builds a new one.
2. Open the APK and allow "Install unknown apps" for your browser or file manager when Android asks. It installs over the old Kharcha app and keeps your data.
3. Open Expensify, tap ☰ (top right) and turn on each permission.

### "Restricted setting" when you turn on the bubble
For sideloaded apps, Android 13+ blocks accessibility and notification access until you allow it:
**Settings → Apps → Expensify → ⋮ (top right) → Allow restricted settings**, then enable it again.

## Build locally
Open the project in Android Studio (JDK 17) and run, or run `gradle :app:assembleDebug`.

The repo includes a fixed signing key (`app/kharcha.keystore`), so each new APK installs over the old one and your data is kept.
