> **Update:** the phone app no longer uses device keys or offers messages. The merchant edits offers inside the app, and credits come from a signed token made on the admin page (`site/admin/`). See `FIRST_RUN_CHECKLIST.md` and `ADMIN_SETUP.md`. The text below describes the earlier version.

# bn

Runs on the phone with no internet. An M-Pesa payment arrives as SMS, the offer is picked by the amount, and the USSD is dialed over the cellular network. Offers are updated by sending a signed SMS from your phone.

```
Customer pays Ksh19 from 0712345678
  -> phone reads the M-Pesa SMS (no internet needed)
  -> offer for Ksh19 is found on the phone
  -> USSD *180*5*2*254712345678*1*1# is dialed (cellular only)
  -> result is logged on the phone

You send  BN$...  by SMS to the phone  ->  signature checked  ->  offers replaced
```

Both M-Pesa message layouts are read (see `bn/parser.py`). The phone uses the sender's number from the receipt as the number to load, so the customer pays from the number they want loaded.

## Why it can't go online

The APK doesn't request the INTERNET permission, so Android won't let it open any network connection.

## Admin workflow

1. **Create a device key** for each phone, once:
   ```bash
   python -m bn.offline keygen
   ```
   Give it to the merchant in person or by a private channel, and keep a copy, because you need it to sign updates.

2. **Set the offers.** Use `{phone}` for the customer's number:
   ```bash
   python -m bn.admin --db bn.db product import 1 products.offers.json
   python -m bn.admin --db bn.db product set 1 99 "1.5GB - 24 hrs" "*180*5*2*{phone}*5*1#"
   ```

3. **Build the update message:**
   ```bash
   python -m bn.offline build --db bn.db --tenant 1 --device-key "PASTE-KEY"
   ```
   It prints one text message and how many SMS parts it needs.

4. **Send it by SMS** from your phone to the merchant's phone, unchanged. Long messages go as several parts automatically.

A message always replaces the whole offer list, so the phone is never left half-updated. The phone accepts only a version newer than the one it has, so old messages can't roll offers back.

## Set up the phone (once)

1. Install the APK. Allow "install unknown apps" for the app you install from.
2. Open **bn** and grant SMS, phone and notification permissions.
3. Paste the device key and tap **Save key**.
4. Turn off battery optimisation (Settings > Apps > bn > Battery > Unrestricted).
5. Keep the SIM inserted. The phone needs cellular signal, not data.

## Security

- **Signed updates.** Each update carries a MAC made with that phone's device key. Forged or edited messages are refused, and the phone logs "Rejected".
- **No rollback.** Updates carry a version (the time they were built). Older versions are refused.
- **Encrypted storage.** The device key, offers and job log are kept in encrypted storage on the phone.
- **No double dialing.** Each M-Pesa code is remembered, so a repeated SMS never dials twice.
- **Failed dials give credits back.** If the network refuses a code, the credits are returned and the job is marked `failed`. A RETRY button appears for a person to decide. A timeout is different: credits stay spent and the job is `unknown`, because the bundle may already be bought.
- **Interrupted dials are not retried.** If the app stops mid-dial, or a dial times out, the job is marked `unknown` and shown on screen for a person to check, because the bundle may already be bought.
- **Expiry.** A payment not dialed within 15 minutes is marked `expired` and not dialed.
- **Only valid codes are dialed.** Every code's format is checked before dialing.

## What this does not protect against

- **Reading the phone's storage.** Someone with the unlocked phone and a rooting tool could read the device key. The key only lets them sign updates for that phone. Keep the phone locked, and rotate the key if it's lost.
- **Losing the phone.** A stolen phone keeps its current offers until you rotate its key.
- **Reinstalling the app.** Reinstalling clears the stored version, so an old signed update could be replayed. Keep updates recent, and don't let the merchant reinstall without you. Full protection needs Device Owner mode, a later step.
- **Other apps on the phone.** This app is offline. The phone itself isn't, unless you lock it down with Device Owner mode.

## Limits

- **No replies to you.** Without internet the phone can't report back. Results show only on its screen.
- **Update SMS cost money**, and the update text shows in the merchant's inbox. Tell them to ignore messages starting with `BN$`.
- **Amounts with no offer** are logged on the phone and not dialed. Add the offer and resend the update.
- **Not compiled here.** The Android code is untested on a device. Expect fixes in Android Studio, especially encrypted storage and the SMS receiver on newer Android versions.
- **Starting the dialer from an SMS.** Newer Android versions can block a background app from starting a service. If that happens, the payment is still recorded and the phone logs "Could not start the dialer". Opening the app restarts it.

## The M-Pesa line

Customers pay into one chosen number. On the phone, open **M-PESA LINE** and tap **USE** on that SIM. Receipts arriving on any other SIM are logged as ignored and never dial. On a phone with one SIM, every receipt counts.

## Testing a Ksh1 receipt

`products.test.json` holds one test offer: Ksh1 dials `*130*<the payer's number>#`, which sends a "please call me" request to whoever paid. The admin page has it as the first offer, with an ON box you untick to switch it off. Use it only for testing:

```bash
python -m bn.admin --db bn.db product import 1 products.test.json
python -m bn.offline build --db bn.db --tenant 1 --device-key "KEY"
```

Send the built update to the phone, then pay Ksh1 to the chosen line. Remove the test offer before going live by setting a real offer for amount 1, or by rebuilding without it.

Automated tests: `python -m unittest tests/test_bn.py` (29 tests, including the Ksh1 case). The Android JUnit tests are in `android/app/src/test/`. Run them from Android Studio with **Run Tests**.

## Credits

One credit per shilling of sale. A Ksh19 sale uses 19 credits, taken before the dial. If the balance is short, the sale is logged as `no_credits` and not dialed. Top-ups add credits with `bn.offline topup`. Credits are kept on the phone.

## Airtime balance

The top bar shows the SIM's airtime, read by dialing `*144#` (Safaricom's airtime balance code). The check runs when the phone is idle, at most every 30 minutes, and costs no credits. Tap the balance to check now. If your network uses a different balance code, change `BALANCE_CODE` in `Store.kt`.

## Git and download site

- The repository is `bn`. Build outputs, keys, databases and APKs are ignored by `.gitignore`. Never commit a signing key.
- `site/` is the download page. Netlify publishes it using `netlify.toml`.
- To publish a new version: build the release APK in Android Studio, copy it to `site/downloads/bn.apk`, commit, and push. Netlify redeploys from the repository.

## Not yet verified

- The Android app has not been compiled or run on a device. Build it in Android Studio and run `Run Tests` before publishing.
- The airtime reply is read by pattern. Confirm the exact wording on your network the first time.

## What you see while it dials

A green DIALING NOW box shows the exact code and a running timer, and the notification shows the same. The phone does not open its dialer screen: Android blocks a background app from doing that, and dialing quietly is what lets the app read the network's reply. REFRESH restarts the dialer if it stopped and asks for a new airtime reading.

See `FIRST_RUN_CHECKLIST.md` before going live.
