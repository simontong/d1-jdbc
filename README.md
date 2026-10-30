# D1 JDBC Connector

[![Build](https://github.com/simontong/d1-jdbc/actions/workflows/build.yml/badge.svg)](https://github.com/simontong/d1-jdbc/actions/workflows/build.yml) [![Release](https://img.shields.io/github/v/release/simontong/d1-jdbc)](https://github.com/simontong/d1-jdbc/releases) [![Licence: MIT](https://img.shields.io/badge/licence-MIT-blue)](LICENSE)

A JDBC driver and JetBrains plugin for Cloudflare D1. Use it in PhpStorm, DataGrip and the other JetBrains IDEs.

It reaches D1 through Cloudflare's REST API, the only way into a live D1 database from outside Cloudflare ([Cloudflare docs](https://developers.cloudflare.com/d1/best-practices/query-d1/)).

## Features

- Cloudflare sign-in, or an API token.
- Every database in one data source, across one or more accounts.
- Tables, views, columns, keys, indexes and foreign keys in the database tree.
- Row editing in the result grid.
- Read-only mode.

## Install

1. Download `d1-jdbc-ide-plugin-<version>.zip` from [Releases](https://github.com/simontong/d1-jdbc/releases).
2. Open **Settings | Plugins | ⚙ | Install Plugin from Disk**, select the zip, and restart the IDE.

## Connect

In the **Database** tool window, click **+ | Data Source | D1 JDBC Connector**, then pick one of these in **Authentication**.

### Cloudflare sign-in

1. Leave **Account** empty.
2. Click **Test Connection**.
3. In the browser, select your accounts, then click **Review permissions** and **Authorize**.

To use one account only, put its ID in **Account**. For a read-only connection, remove **D1 Write** under **Edit Permissions** on Cloudflare's page.

The sign-in is saved in `d1-jdbc/cloudflare-sign-in.json` in your config folder: `%APPDATA%` on Windows, otherwise `$XDG_CONFIG_HOME` or `~/.config`. Delete the file to sign in again. To revoke access, open **My Profile | Access Management | Connected Applications** in the Cloudflare dashboard.

> [!NOTE]
> For now, Cloudflare sign-in works only for members of the Cloudflare account that owns the sign-in app. Everyone else can use an API token.

### API token

1. [Create your token](https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22d1%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=D1%20JDBC) with D1 Edit, or D1 Read for read-only use.
2. Copy your account ID: press Ctrl+K (Cmd+K on a Mac) in the Cloudflare dashboard, then select **Copy account ID**.
3. Put your account ID in **Account** and your token in **API token**, then click **Test Connection**.

> [!IMPORTANT]
> Your token can reach every D1 database in your account, as Cloudflare tokens can't be limited to one database.

## Databases

SQL runs on the first database by name until you pick another in the console's database switcher. To start on a set database, put its name or ID in **Database**. Use the ID when two databases share a name.

## Without the plugin

Add `d1-jdbc-<version>-all.jar` from [Releases](https://github.com/simontong/d1-jdbc/releases) as a [custom driver](https://www.jetbrains.com/help/datagrip/jdbc-drivers.html) with class `nz.co.simontong.d1jdbc.D1Driver` and dialect SQLite.

| Authentication | URL | User and password |
| --- | --- | --- |
| Cloudflare sign-in | `jdbc:d1://all?auth=browser` | Empty |
| API token | `jdbc:d1://<account id>` | Empty user, token as the password |

To start on a set database, add its name or ID: `jdbc:d1://<account id>/<database>`.

## Settings

Add settings to the URL, for example `?readOnly=true`, or set them on the data source's **Advanced** tab.

| Setting | Default | Description |
| --- | --- | --- |
| `auth` | `token` | `token` for an API token, `browser` for Cloudflare sign-in. |
| `password` | none | The API token. Not accepted in the URL, as IDEs save URLs as plain text. |
| `readOnly` | `false` | Blocks statements that change data. |
| `schemaCacheSeconds` | `30` | Seconds to keep the table list. `0` reads it every time. |
| `tokenFile` | see [Cloudflare sign-in](#cloudflare-sign-in) | Where the sign-in is saved. |
| `openBrowser` | `true` | `false` stops a connection from opening the browser. The plugin sets this for background connections. |

Read-only mode is enforced by the driver. For a guarantee from Cloudflare, give your token D1 Read only, or sign in without D1 Write.

## Limits

- D1 has no transactions. Each statement is saved when it runs, so rollback is not possible. A batch, or SQL with several statements, is saved completely or not at all.
- If Cloudflare does not reply, a change may or may not have been saved. The error says so.
- Whole numbers larger than 9,007,199,254,740,992 lose their last digits when read, as Cloudflare sends them as JSON decimals. Use `cast(x as text)` to read every digit.
- Cloudflare allows 1,200 API calls per 5 minutes per user, then blocks all calls for 5 minutes ([Cloudflare docs](https://developers.cloudflare.com/fundamentals/api/reference/limits/)). The driver keeps the table list for 30 seconds to make fewer calls.
- A statement can have up to 100 bound values ([Cloudflare docs](https://developers.cloudflare.com/d1/platform/limits/)). SQL with several statements cannot have bound values.
- Grid editing needs a query on one table, with no joins, grouping or unions.
- The only generated key returned after an insert is the row ID.
- Primary and foreign keys have names only when `CREATE TABLE` names them.
- Virtual tables, such as full-text search tables, are not listed in the tree. You can still query them.

## Build

Needs Java 17 or later. Gradle downloads any other Java version the build uses.

```sh
./gradlew build
```

| Output | Path |
| --- | --- |
| Driver | `build/libs/d1-jdbc-<version>-all.jar` |
| Plugin | `ide-plugin/build/distributions/d1-jdbc-ide-plugin-<version>.zip` |

- `-PlocalIde=<path to PhpStorm>` compiles the plugin against an installed PhpStorm, instead of downloading one.
- `-PreleaseVersion=<version>` points the plugin at the driver in the matching GitHub release. Without it, the plugin uses the driver in `build/libs`.
- The live tests run when `.env` has a test database. See `.env.example`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence

MIT. See [LICENSE](LICENSE).

D1 JDBC Connector is not affiliated with or endorsed by Cloudflare, Inc. Cloudflare and the Cloudflare logo are trademarks and/or registered trademarks of Cloudflare, Inc. in the United States and other jurisdictions.
