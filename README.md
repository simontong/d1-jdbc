# D1 JDBC Connector

Browse, query and edit your Cloudflare D1 databases in PhpStorm, DataGrip and the other JetBrains IDEs.

D1 JDBC Connector is a JDBC driver and an IDE plugin. The driver sends your SQL to Cloudflare's REST API. Outside of Cloudflare, this API is the only way to reach a live D1 database ([Cloudflare docs](https://developers.cloudflare.com/d1/best-practices/query-d1/)).

## Features

- Sign in with Cloudflare in the browser, or use an API token.
- See every D1 database in your account in one connection.
- Run queries, changes, batches, and SQL that holds more than one statement.
- Use prepared statements with `?` and `?1` placeholders.
- Browse tables, views, columns, primary keys, indexes and foreign keys.
- Edit rows in the result grid.
- Block changes with read-only mode.

## Contents

- [Install the plugin](#install-the-plugin)
- [Connect with Cloudflare sign-in](#connect-with-cloudflare-sign-in)
- [Connect with an API token](#connect-with-an-api-token)
- [Choose a database](#choose-a-database)
- [Use the driver without the plugin](#use-the-driver-without-the-plugin)
- [Settings](#settings)
- [Limits](#limits)
- [Build from source](#build-from-source)

## Install the plugin

1. Download `d1-jdbc-ide-plugin-<version>.zip` from the [releases page](https://github.com/simontong/d1-jdbc/releases).
2. In the IDE, open **Settings | Plugins**.
3. Click ⚙, then **Install Plugin from Disk**, and select the zip.
4. Restart the IDE.

The plugin adds **D1 JDBC Connector** to the list of data sources. The first time that you connect, the IDE downloads the driver.

## Connect with Cloudflare sign-in

With this option, you do not need an API token or your account ID.

1. In the **Database** tool window, click **+**, then **Data Source | D1 JDBC Connector**.
2. In **Authentication**, select **Cloudflare sign-in**.
3. Leave **Account** empty.
4. Click **Test Connection**. Your browser opens Cloudflare's sign-in page.
5. Select the accounts to use, then click **Review permissions** and **Authorize**.

When the browser shows "You're signed in", go back to the IDE.

The tree shows the databases of every account that you selected. If you selected more than one account, each database name starts with its account name, for example `Main / shop`.

To use one account only, put its ID in **Account**.

### Read-only sign-in

On Cloudflare's page, click **Edit Permissions** and remove **D1 Write**. The connection is then read-only.

### Sign in again or sign out

The driver keeps your sign-in in a file in your config folder. On Linux and macOS, only you can read the file. The driver gets a new token each hour, so you sign in once on each computer.

- To sign in again, delete the file, then connect. The file is `d1-jdbc/cloudflare-sign-in.json` in your config folder: `%APPDATA%` on Windows, otherwise `$XDG_CONFIG_HOME` or `~/.config`.
- To remove the app from your Cloudflare account, open **My Profile | Access Management | Connected Applications** in the Cloudflare dashboard.

The IDE opens the browser only when you connect. When the IDE connects in the background and you are not signed in, the connection stops and asks you to connect.

> [!NOTE]
> At the moment, only members of the Cloudflare account that owns the sign-in app can use Cloudflare sign-in. Everyone else can use an API token.

## Connect with an API token

### 1. Make the token

Open this link while you are logged in to Cloudflare. It fills in a token with D1 Edit permission:

[Create a D1 API token](https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22d1%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=D1%20JDBC)

Select your account, then create the token. For read-only use, change the permission to **D1 Read** first.

> [!IMPORTANT]
> The token can reach every D1 database in the account. Cloudflare tokens cannot be limited to one database.

### 2. Find your account ID

In the Cloudflare dashboard, press Ctrl+K (Cmd+K on a Mac), then select **Copy account ID** ([Cloudflare docs](https://developers.cloudflare.com/fundamentals/account/find-account-and-zone-ids/)).

### 3. Add the data source

1. In the **Database** tool window, click **+**, then **Data Source | D1 JDBC Connector**.
2. In **Authentication**, select **API token**.
3. Put your account ID in **Account**, and the token in **API token**.
4. Click **Test Connection**.

The IDE keeps the token in its password store.

## Choose a database

D1 has no default database, so the driver uses the first database by name until you pick one. To pick the database that your SQL runs on, use the database switcher in the query console.

To start on one database, put its name or ID in **Database**. If two databases have the same name, use the ID. The ID is in the `uuid` column of `wrangler d1 list`.

## Use the driver without the plugin

The driver works in any JDBC tool. These steps are for a JetBrains IDE.

1. Download `d1-jdbc-<version>-all.jar` from the [releases page](https://github.com/simontong/d1-jdbc/releases).
2. Open **Data Sources and Drivers** (Ctrl+Alt+Shift+S), then go to the **Drivers** tab.
3. Add a driver, and add the jar as a custom JAR in **Driver Files**.
4. Set **Class** to `nz.co.simontong.d1jdbc.D1Driver`.
5. On the **Options** tab, set **Dialect** to SQLite.
6. Add a data source that uses the driver, and set the URL.

| To connect with | URL | User and password |
| --- | --- | --- |
| Cloudflare sign-in | `jdbc:d1://all?auth=browser` | Leave both empty. |
| An API token | `jdbc:d1://<account id>` | Leave the user empty. Put the token in the password. |

To start on one database, add its name or ID to the URL, for example `jdbc:d1://<account id>/shop`.

The JetBrains help has more about [custom drivers](https://www.jetbrains.com/help/datagrip/jdbc-drivers.html).

## Settings

Add settings to the end of the URL, for example `jdbc:d1://<account id>?readOnly=true`. You can also set them on the **Advanced** tab of the data source.

| Setting | Default | What it does |
| --- | --- | --- |
| `auth` | `token` | `token` uses the API token in the password. `browser` signs in with Cloudflare in the browser. |
| `password` | none | Your API token. The driver does not accept it in the URL, because IDEs save URLs as plain text. |
| `readOnly` | `false` | Blocks statements that change data. |
| `schemaCacheSeconds` | `30` | How long the driver keeps the table list before it reads it again. `0` reads it every time. |
| `tokenFile` | see [Sign in again](#sign-in-again-or-sign-out) | The file that keeps the Cloudflare sign-in. |
| `openBrowser` | `true` | `false` stops a connection from opening the browser to sign in. The plugin sets this for background connections. |

Read-only mode is a check in the driver. For a guarantee from Cloudflare, also use a token with D1 Read permission, or sign in without D1 Write.

## Limits

- **No transactions.** Each statement is saved when it runs, so you cannot roll back. A batch, or SQL that holds more than one statement, is saved completely or not at all.
- **No reply from Cloudflare.** If Cloudflare does not reply, a change can be saved or not saved. The error message tells you this.
- **Large numbers.** Whole numbers larger than 9,007,199,254,740,992 can lose their last digits when you read them, because Cloudflare sends them as JSON decimals. The database stores them exactly. To read every digit, use `cast(x as text)`.
- **Rate limit.** Cloudflare allows 1,200 API calls in 5 minutes for each Cloudflare user, including calls from the dashboard and your other tokens. After that, Cloudflare blocks all calls for 5 minutes ([Cloudflare docs](https://developers.cloudflare.com/fundamentals/api/reference/limits/)). The driver keeps the table list for 30 seconds, and shares it between connections, to make fewer calls. A refresh in the IDE always reads it again.
- **Bound values.** A statement can have a maximum of 100 bound values ([Cloudflare docs](https://developers.cloudflare.com/d1/platform/limits/)). SQL that holds more than one statement cannot have bound values.
- **Grid editing** works only for a query that reads from one table, with no joins, grouping or unions.
- **Generated keys.** After an insert, the only generated key that the driver can return is the row ID.
- **Key names.** Primary keys and foreign keys have names only when the `CREATE TABLE` statement gives them names.
- **Virtual tables**, for example full-text search tables, do not show in the tree. You can still query them.

## Build from source

You need Java 17 or later to run the build. Gradle downloads any other Java version that the build needs.

```sh
./gradlew build
```

The build makes two files:

| File | Path |
| --- | --- |
| The driver | `build/libs/d1-jdbc-<version>-all.jar` |
| The plugin | `ide-plugin/build/distributions/d1-jdbc-ide-plugin-<version>.zip` |

The plugin build downloads PhpStorm to compile against, which is large. To use an installed PhpStorm, add `-PlocalIde=<path to PhpStorm>`.

A release build, made with `-PreleaseVersion=<version>`, sets the plugin to download the driver from the GitHub release with the same version. Any other build sets the plugin to use the driver jar in `build/libs`, so that you can test the plugin before a release.

The tests use a stub server and an in-memory SQLite database. To also run the live tests, copy `.env.example` to `.env`, then add the details of a test database. The live tests make and delete their own tables.

## Licence

MIT. See [LICENSE](LICENSE).

D1 JDBC Connector is not affiliated with or endorsed by Cloudflare, Inc. Cloudflare and the Cloudflare logo are trademarks and/or registered trademarks of Cloudflare, Inc. in the United States and other jurisdictions.
