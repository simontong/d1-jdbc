# D1 JDBC driver

A JDBC driver for Cloudflare D1, so you can browse, query and edit a remote D1 database from PhpStorm, DataGrip or any other JDBC tool. It talks to D1 through Cloudflare's REST API, which is the only way to reach a live D1 database from outside Cloudflare (https://developers.cloudflare.com/d1/best-practices/query-d1/).

It's written in Kotlin and ships as one jar with everything it needs inside it.

## What works

- Running queries and changes, including several statements at once and batches.
- Prepared statements with `?` and `?1`-style placeholders.
- Browsing tables, views, columns, primary keys, indexes and foreign keys.
- Editing rows in the result grid for queries that read from one table.
- A read-only mode that blocks anything that would change data.

## Set it up in PhpStorm or DataGrip

You'll need a Cloudflare API token, your account ID and your database ID.

### 1. Make an API token

Open this link while logged in to Cloudflare. It fills in a token with D1 Edit permission, and you just pick your account and create it.

https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22d1%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=D1%20JDBC

If you only want to read, change the permission to D1 Read before you create it. The link format comes from Cloudflare's token template docs (https://developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template/).

Cloudflare tokens can't be limited to one database, so the token can reach every D1 database in the account.

To find your account ID, press Ctrl+K (or Cmd+K) in the Cloudflare dashboard and choose Copy account ID (https://developers.cloudflare.com/fundamentals/account/find-account-and-zone-ids/). Your database ID is in the `uuid` column of `wrangler d1 list`.

### 2. Add the driver

1. Download `d1-jdbc-<version>-all.jar` from the releases page.
2. Open Data Sources and Drivers (File | Data Sources, or Ctrl+Alt+Shift+S), go to the Drivers tab, and add a new driver called D1 (https://www.jetbrains.com/help/datagrip/other-databases.html).
3. In Driver Files, click Add, choose Custom JARs, and pick the jar (https://www.jetbrains.com/help/datagrip/jdbc-drivers.html).
4. Set Class to `nz.co.simontong.d1jdbc.D1Driver`.
5. On the Options tab, set Dialect to SQLite.

### 3. Add the database

1. Add a data source that uses the D1 driver.
2. Set the URL to `jdbc:d1://<account id>/<database id>`.
3. Leave the user empty and put your API token in the password field.
4. Turn on "Introspect using JDBC metadata". JetBrains' docs place it in the Expert options on either the driver's or the data source's Advanced tab (https://www.jetbrains.com/help/datagrip/data-sources-and-drivers-dialog.html and https://www.jetbrains.com/help/datagrip/cannot-find-a-database-object-in-the-database-tree-view.html).
5. Click Test Connection.

The IDE turns "Introspect using JDBC metadata" off after it updates and shows a notice, so you may need to turn it back on (https://youtrack.jetbrains.com/issue/DBE-14258).

## Settings

You can add these to the end of the URL, like `jdbc:d1://<account id>/<database id>?readOnly=true`, or set them on the driver's Advanced tab.

| Setting | Default | What it does |
| --- | --- | --- |
| `password` | none | Your Cloudflare API token. Required. |
| `readOnly` | `false` | Blocks statements that change data. |
| `schemaCacheSeconds` | `30` | How long to reuse the table list before asking D1 again. `0` asks every time. |

Read-only mode is a check inside the driver. For a real guarantee, use a token with D1 Read permission too, as D1 rejects `PRAGMA query_only` (see `docs/research/live-tests.md`).

## Known limits

- D1 has no transactions you can control. Every statement is saved as soon as it runs, so rollback isn't possible. A batch, or SQL holding several statements, is saved in full or not at all. These come from live tests recorded in `docs/research/live-tests.md`.
- Whole numbers bigger than 9,007,199,254,740,992 can lose their last digits when read back, because Cloudflare's JSON reply turns them into decimals. They're stored exactly, so `cast(x as text)` reads every digit.
- Cloudflare allows 1,200 API calls per 5 minutes for each token, and blocks every call for 5 minutes after that (https://developers.cloudflare.com/fundamentals/api/reference/limits/). The driver loads the whole schema in two calls and keeps it for 30 seconds to stay well under this.
- Each statement can have at most 100 bound values (https://developers.cloudflare.com/d1/platform/limits/). SQL holding several statements can't have `?` values at all, which D1 rejects (see `docs/research/live-tests.md`). Batches of separate statements are fine.
- Only queries that read from one table, with no joins, grouping or unions, can be edited in the grid. The driver works out the table from the SQL, as D1 doesn't say where result columns come from.
- After an insert, the only generated key the driver can return is the new row ID.
- Foreign keys have no names, as SQLite doesn't report them.
- D1 doesn't send column types with results, so the type of a column in a result comes from its values, or from the table when the query reads from one table.

## Build it yourself

You need Java 17 or newer.

```sh
./gradlew build
```

The jar is `build/libs/d1-jdbc-<version>-all.jar`.

The tests run against a stub server and an in-memory SQLite database. To run the live tests too, copy `.env.example` to `.env` and fill in a throwaway database's details. The live tests create and drop their own tables.

## Licence

MIT. See `LICENSE`.
