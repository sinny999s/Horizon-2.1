# Horizon share

A small Cloudflare Worker that lets Horizon users see each other's Lunar locker picks (client-side cosmetics too).
Horizon's side is `src/main/kotlin/dev/horizon/client/lunar/HorizonShare.kt`. Free Cloudflare plan is enough
(Workers + one D1 database).

## Deploy (once)

Run these in this folder (`share-worker/`). You need Node.js (already installed) and a free Cloudflare account.

1. Install the tools:

       npm install

2. Sign in to Cloudflare (opens your browser):

       npx wrangler login

3. Make the database, then put the `database_id` it prints into `wrangler.toml`:

       npx wrangler d1 create horizon-share

4. Make its table:

       npx wrangler d1 execute horizon-share --remote --file=schema.sql

5. Set the token secret (type any long random text when asked; keep it private):

       npx wrangler secret put TOKEN_SECRET

6. Deploy:

       npx wrangler deploy

   It prints the address, like `https://horizon-share.<your-name>.workers.dev`. Put that in
   `HorizonShare.DEFAULT_URL` and rebuild Horizon so every Horizon user gets it (or set `share-url` in a user's
   `horizon/lunar.hzn`).

Never set `DEV_INSECURE` on the deployed Worker: it's only for local tests (`.dev.vars`), where it lets an offline
account sign in without Mojang.

## How it works

- `POST /auth {name, serverId}`: the game joined a Mojang session with `serverId`; the Worker asks Mojang
  (`hasJoined`) who did, and returns a token for that account (a day, HMAC-signed).
- `POST /picks {token, picks}`: your locker picks (up to 24 cosmetic ids; `[]` removes them).
- `POST /lookup {token, uuids}`: the picks of those players (up to 64) that are Horizon users with picks shared.
- Picks not updated for 7 days are ignored. Only signed-in accounts can look anything up.

## Test locally

    printf 'TOKEN_SECRET=local-test-secret\nDEV_INSECURE=1\n' > .dev.vars
    npx wrangler d1 execute horizon-share --local --file=schema.sql
    npx wrangler dev --local --port 8787

Then `tools/examples/lunar-share.txt` (its header says how to point the dev client at it).
