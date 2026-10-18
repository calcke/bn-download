# Hosting on GitHub (no Netlify, no credits)

Everything is now served by **GitHub Pages**, which is free. Your private `bn` repo builds the app and the site; a second **public** repo, `bn-download`, hands them out.

| What | Where |
|---|---|
| Landing page | `https://calcke.github.io/bn-download/` |
| Admin page | `https://calcke.github.io/bn-download/admin/` |
| APK for customers | `https://github.com/calcke/bn-download/releases/latest/download/bn.apk` |
| APK for you | your private `bn` repo, **Releases** (it is a plain `bn.apk`, not a zip) |

Why the public repo: GitHub Pages and public downloads are free only for public repos, and your `bn` repo must stay private (it holds the app's source).

## One-time setup (about 10 minutes)
1. **Make the public repo.** GitHub > New repository. Name `bn-download`. Public. Tick "Add a README". Create it.
2. **Make a token.** GitHub > your picture > Settings > Developer settings > Personal access tokens > **Fine-grained tokens** > Generate new token.
   Repository access: **only** `bn-download`. Permissions: **Contents: Read and write**. Copy the token.
3. **Add two secrets** in your private `bn` repo: Settings > Secrets and variables > Actions > New repository secret.
   - `RELEASE_REPO` = `calcke/bn-download`
   - `RELEASE_TOKEN` = the token
4. **Turn on Pages** in `bn-download`: Settings > Pages > Build and deployment > Source: **Deploy from a branch**, Branch **main**, folder **/ (root)**, Save.
5. In `bn`, open **Actions**. Run **Build APK** (it publishes the APK) and **Publish site to GitHub Pages** (it copies the site). Both are green when done. After a minute or two the links above work.

From now on:
- Push app changes (`android/`): the APK builds and appears under Releases, in both repos. Nothing else to do.
- Push site changes (`site/`): the site copies itself to `bn-download` and Pages updates in a few minutes.

## Move your admin page (do this BEFORE you stop using Netlify)
The old admin page kept its data on Netlify. The new one keeps it **in your browser** (still locked with your password).
1. On the OLD admin page (bngw.netlify.app/admin/): open **Backup** > **Download backup**. Keep the file private.
   If Netlify is blocked because it ran out of credits, you cannot open it until the credits reset. In that case, if you have an older backup file, use that.
2. On the NEW admin page: choose a password (twice), then **Backup** > **Restore from backup** and pick the file. Your app key and token history are back.
3. Download a fresh backup from the new page and keep it safe. In this mode the backup is your only copy: clearing the browser data, or using another computer, loses the data. On another computer, open the admin page and restore the backup file.

If you have NO backup and cannot reach the old admin page: make a new app key on the new page, put the new public key into `android/app/src/main/assets/public_key.txt`, and rebuild. Phones with the old build will not accept new tokens, so everyone must install the new build.

## Stop Netlify from using credits
In Netlify open your site > Project configuration > Build & deploy > Continuous deployment > **Stop builds** (or delete the project once the new pages work).

## Still says "App not installed"?
- Uninstall the old bn once, then install. (Builds signed with a different key cannot be installed over each other.)
- Open the APK from the **Files** app instead of the browser's download list.
- Check the build was signed: in the Actions run, the step "Prepare signing key" must not say "No signing secrets found".
