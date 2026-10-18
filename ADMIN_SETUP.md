# Admin page: online, behind your password

The admin page lives at `https://YOUR-SITE.netlify.app/admin/`. It asks for your password, and your app key
and token history are locked with that password in the browser before they are saved online. Netlify only ever
stores the locked copy.

## One-time setup (about 3 minutes)

1. Upload this project to GitHub as usual (`site/admin/`, `netlify/functions/vault.mjs`, `package.json`, `netlify.toml`).
2. In Netlify open your site, then **Site configuration > Environment variables > Add a variable**.
   - Key: `ADMIN_PASSWORD`
   - Value: a long password you will remember (at least 10 characters). A few random words works well.
   - Click **Create variable**.
3. Go to **Deploys > Trigger deploy > Deploy site** so the password takes effect.
4. Open `/admin/`, type the password. The first time, an empty vault is created for you.

## Things to know

- **Wrong tries:** 8 wrong passwords in 15 minutes shuts the door for 15 minutes (this also blocks you).
- **Changing the password:** Backup tab > Download backup. Change `ADMIN_PASSWORD` in Netlify and redeploy. Open the page, then Backup > Restore from backup. (The old saved data is locked with the old password and cannot be opened by the new one, so do the backup first.)
- **Forgotten password:** the saved data cannot be opened by anyone, including you. Keep a backup file.
- **Two devices at once:** if you edit on two devices at the same time, the second save is refused with a message. Lock, unlock, and redo that edit.
- The page locks itself after 15 minutes of no use.
- Netlify free plan is enough.

## What the admin page does now

- **1. App key:** made once. It is a matching pair. The private half stays in your locked vault. The public half goes into the phone app (`android/app/src/main/assets/public_key.txt`, one line). Never make a second key: apps built with the first one would reject your tokens.
- **2. Credit tokens:** type the phone's Device ID (shown in the app) and a name, choose the credits, make the token, send it. A token works once, on that phone only.
- Offers are not managed here any more. The merchant edits them inside the app.
- Old phone keys and offers saved by the earlier version are still in your vault, just not shown.
