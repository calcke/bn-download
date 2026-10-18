# First-run checklist (do this once, before real customers)

Takes about 20 minutes and needs Ksh 1 plus a little airtime. Do the steps in order.

## A. Website and admin (saves build credits: one commit)
1. Upload ALL changed files to GitHub in ONE commit (GitHub web: Add file > Upload files, one "Commit changes").
2. Netlify: `ADMIN_PASSWORD` must already be set (Site configuration > Environment variables). Trigger a deploy if the site did not rebuild.
3. Open `/admin/`, type the password.

## B. Make the app key (once)
1. Admin tab 1: tap **Make app key**. Tap **Copy public key**.
2. GitHub: open `android/app/src/main/assets/public_key.txt`, click the pencil, delete everything, paste the key as ONE line, commit. This starts the APK build (Actions tab, wait for the green tick).
3. Admin > Backup > **Download backup** and keep the file private. It holds your private key.
4. Install the new APK on the phone (it installs over the old one).

## C. The phone
1. Open bn and allow every permission. If SMS is blocked: Settings > Apps > bn > top-right menu > Allow restricted settings, then ALLOW again.
2. The top of the screen shows the LISTENING switch, AIRTIME and CREDITS. Tap the AIRTIME tile: it dials *144# and shows your balance.
3. OFFERS lists the starting data bundles. Tap any offer to edit it, or **+ ADD** to make your own. To test with Ksh 1: + ADD, type DATA or SMS, amount `1`, what they get `please call me`, code `*130*n#`.
4. Minutes offers need your own USSD codes: tap one, add the code, SAVE (it turns on by itself).
5. Credits: copy the YOUR DEVICE ID from ADD CREDITS and send it to whoever sells you credits. In admin tab 2 type that ID, a name, 100 credits, Make token, copy it, send it. On the phone: copy the token, tap PASTE, tap ADD CREDITS. CREDITS should show 100.
6. On the first screen, the **Finish setup** card lists what is left: allow permissions, turn on the **bn USSD helper** in Accessibility (use "Allow restricted settings" first if it is greyed out), and choose your M-Pesa SIM if there are two. The card disappears when you are done.
7. Keep the phone plugged in with no screen lock. The status line on HOME must say READY.

## D. The real test
1. From another phone, pay Ksh 1 to the till.
2. bn shows a DIALING NOW box with the code. In SALES the line says DONE with the date, the customer's number and what they got. CREDITS drops by 1.
3. Switch LISTENING off, pay again: the payment is ignored and the log says so. Switch it on again.
4. Tap **Open activity log** (bottom of the main screen). Every payment, dial attempt, success and failure is listed with date and time, newest first. Red = problem, green = sale done. Use PROBLEMS ONLY to see just what failed, and COPY to send the log to whoever helps you.

## E. Before going live
1. Delete or turn off the Ksh 1 test offer.
2. Check every offer code against your own account.

## F. Things added later
1. **Codes:** write them the usual way, for example `*188*5*2*N*1*1#` (N = the customer's number). The app always dials such codes one step at a time: `*188#`, then it types 5, 2, the number, 1, 1. Short codes like `*130*N#` are dialed in one go.
2. **Hidden numbers:** a contact called `BN_<NAME ON THE RECEIPT>` is used first. If there is none, the receipt is forwarded to 334 and the customer approves. Check the log (Sales > Open activity log) for the lines "Number known..." or "No number...". A sale that waited too long shows under NEEDS ACTION with an ENTER NUMBER button.
3. **Customer messages:** Settings > Messages to customers. The failure message is on by default; the success message is off.
4. **Offer times:** open an offer and fill OPEN FROM and OPEN UNTIL (for example 06:00 and 23:00).
5. **Test a code:** open the offer, scroll to TEST THIS CODE, type your own number, choose how many of the last steps NOT to send (1 by default), and tap RUN TEST. The phone dials the steps and shows the menu the buyer would see. If the purchase goes through anyway, the credits for it are used, like a sale.
6. **Retry:** failed sales appear under NEEDS ACTION on the SALES tab with a RETRY button each. Credits are only used when the bundle is delivered.
7. **Hosting:** see `GETTING_THE_APK.md`. The site, admin page and APK are all served free by GitHub. No Netlify deploy is needed.
8. **Airtime balance:** read when you open the app (at most every 15 minutes) and once an hour in the background.
