# FeatherMC

A Paper 1.21.1 plugin bundling: Punish GUI, Warps, Egg Shop (virtual
currency), Duels (with Multiverse-Core arena cloning), Auction House,
Order System, and Playtime rewards - all behind a single `/feathermc`
command, with every player-facing message and setting configurable.

## Requirements

- Java 21, Maven, Paper 1.21.1
- **Vault + any economy plugin** (EssentialsX Economy, etc.) → needed for **Auction House** and **Order System** (your server's real money). *Eggs do not need Vault - see below.*
- **Multiverse-Core** → needed for **Duels** world creation (falls back to a plain Bukkit world if absent)
- **PlaceholderAPI** → optional, adds `%feathermc_eggs%` / `%feathermc_eggs_formatted%`
- **LuckPerms** → not a hard dependency in code. Auction House / Order slot limits work off standard Bukkit permission checks, which LuckPerms satisfies automatically once you grant the nodes in `config.yml` to your groups.

## Why eggs aren't "real" Vault currency

You asked for eggs to work "like Vault" but under their own `/feathermc eggs`
command. Vault only lets **one** plugin register the primary Economy at a
time, and that slot needs to stay free for your main economy plugin (which
the Auction House and Order System use). So FeatherMC ships its own small
egg ledger (`EggEconomy`) with the same deposit/withdraw/balance shape
Vault would give you, just kept separate. It's not registered as a Vault
provider, so other Vault-based plugins won't see egg balances - only
FeatherMC's own shop and `/feathermc eggs` commands do. If you'd rather
have eggs actually be a second Vault-registered economy (some economy
plugins support multi-currency via their own custom API, e.g. certain
CMI/TokenManager setups), that's a different integration - let me know and
I can wire it to a specific plugin's API instead.

## Building

```bash
cd megaplugin
mvn clean package
```

Shaded jar lands at `target/FeatherMC.jar`. I don't have internet access
in my sandbox so I can't compile/test this myself - please build it and
send me any compiler errors so I can fix them fast. This is a large
project across 16 files, so I'd treat the first build as a "shake the bugs
out" pass rather than assume it's perfect.

## Building without installing anything (GitHub Actions)

If you don't want to install Java/Maven locally, push this folder to a
GitHub repo - `.github/workflows/build.yml` is already set up to compile
it for you:

1. Create a new repo on GitHub and push this project to it (or drag-and-drop
   upload the files through the GitHub web UI).
2. Go to the repo's **Actions** tab - a "Build FeatherMC" run starts
   automatically on push (or click **Run workflow** to trigger it manually).
3. Once it finishes, open the run and download the **FeatherMC-jar**
   artifact from the bottom of the page - that's your compiled plugin jar.

There's also `.github/workflows/release.yml`: push a version tag and it
builds the jar and attaches it directly to a new GitHub Release, so you
(or anyone else running the plugin) always has a downloadable link:

```bash
git tag v1.0.0
git push origin v1.0.0
```

## Everything is one command

```
/feathermc punish <player>
/feathermc warp [name]
/feathermc warp set <name>
/feathermc warp del <name>
/feathermc shop
/feathermc duel <player|accept|deny|cancel|arenas>
/feathermc duel pos1 / pos2 / generate      (setup, feathermc.duel.admin)
/feathermc ah
/feathermc order
/feathermc playtime
/feathermc eggs [player]
/feathermc eggs pay <player> <amount>
/feathermc eggs give|take|set <player> <amount>   (admin)
/feathermc reload
```
`/fmc` is a built-in alias. Tab completion is wired up for every subcommand.

## Messages

Every player-facing string lives in `plugins/FeatherMC/messages.yml`
(auto-created from the jar's defaults on first run, editable freely with
`&` color codes and `%placeholder%` substitutions). `/feathermc reload`
reloads both `config.yml` and `messages.yml` without a restart. If you add
new message keys in a future update of the jar, they'll be merged into the
server's existing `messages.yml` automatically without overwriting your edits.

## Auction House & Order System GUIs

Both are now full in-game menus, not just commands:

- **`/feathermc ah`** - paginated grid of live listings (bottom row:
  previous page / **Sell Held Item** / page indicator / next page).
  Clicking **Sell Held Item** closes the menu and asks you to type a price
  in chat (or `cancel`); it lists whatever you were holding at that price.
  Clicking someone else's listing buys it instantly (Vault); clicking your
  own reclaims it.
- **`/feathermc order`** - same layout, with a **Create Order** button.
  It captures the item in your hand, then asks you to type
  `<price> <amount>` in chat to fund and post the order. Other players
  click a listed order while holding matching items to sell straight into
  it for instant payment.

Live-listing/order caps still come from `slot-permissions` in
`config.yml`, matched against whatever LuckPerms permission the player has:

```
/lp group vip permission set feathermc.ah.slots.5 true
/lp group vip permission set feathermc.order.slots.5 true
```

## Duels (Multiverse-Core arena cloning) - unchanged from before

1. Build a template arena anywhere.
2. `/feathermc duel pos1` at one player-spawn corner, `/feathermc duel pos2` at the other.
3. `/feathermc duel generate` - creates/uses the `duel` Multiverse world and
   block-copies the template into `duels.arena-count` (default 50) spaced
   arenas, saved to `duel_arenas.yml`.
4. `/feathermc duel <player>` → `/feathermc duel accept` to fight.

## Egg Shop

`/feathermc shop` - left-click buys with your egg balance, right-click
sells for eggs. Items configured under `shop.items` in `config.yml`.

## Playtime

Tracks automatically; `/feathermc playtime` opens the milestone GUI (every
2 hours by default, 1 → 1000, both configurable). Reward commands are
under `playtime.reward-commands.default` with optional per-hour overrides.

## Data files (auto-created in `plugins/FeatherMC/`)

`warps.yml`, `duel_arenas.yml`, `auctions.yml`, `orders.yml`,
`playtime.yml`, `eggs.yml`, `messages.yml`
