# D1 JDBC Connector

A JDBC driver for Cloudflare D1. Use it to browse, query and edit D1 databases in DataGrip, PhpStorm and other JetBrains IDEs, or in any JDBC tool.

The driver sends SQL to Cloudflare's REST API. Outside of Cloudflare, this API is the only way to reach a live D1 database (https://developers.cloudflare.com/d1/best-practices/query-d1/).

## Features

- One connection shows every D1 database in the account, each by its name.
- Run queries, changes, batches, and SQL that holds more than one statement.
- Prepared statements with `?` and `?1` placeholders.
- Browse tables, views, columns, primary keys, indexes and foreign keys.
- Edit rows in the result grid when the query reads from one table.
- Read-only mode blocks statements that change data.

## What you need

- A Cloudflare API token with D1 permission.
- Your Cloudflare account ID.

### Make the API token

Open this link while you are logged in to Cloudflare. The link fills in a token with D1 Edit permission. Select your account, then create the token.

https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22d1%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=D1%20JDBC

For read-only use, change the permission to D1 Read before you create the token.

The token can reach every D1 database in the account. Cloudflare tokens cannot be limited to one database.

### Find your account ID

In the Cloudflare dashboard, press Ctrl+K (Cmd+K on a Mac), then select Copy account ID (https://developers.cloudflare.com/fundamentals/account/find-account-and-zone-ids/).

## Set up the IDE

### With the plugin

1. Download the plugin jar from the releases page.
2. In the IDE, open Settings | Plugins | ⚙ | Install Plugin from Disk, and select the jar.
3. Add a data source and select D1 JDBC Connector. The IDE downloads the driver.
4. Do the steps in "Connect to D1".

### Without the plugin

1. Download `d1-jdbc-<version>-all.jar` from the releases page.
2. Open Data Sources and Drivers (Ctrl+Alt+Shift+S), then go to the Drivers tab.
3. Add a driver with the name D1.
4. In Driver Files, add the jar as a custom JAR.
5. Set Class to `nz.co.simontong.d1jdbc.D1Driver`.
6. On the Options tab, set Dialect to SQLite.
7. Do the steps in "Connect to D1".

The JetBrains help has more about custom drivers (https://www.jetbrains.com/help/datagrip/jdbc-drivers.html).

### Connect to D1

1. Add a data source that uses the D1 driver.
2. Set the URL to `jdbc:d1://<account id>`.
3. Leave the user empty. Put the API token in the password field.
4. Click Test Connection.

The tree shows each database by name. To pick the database that your SQL runs on, use the database switcher in the query console.

To start on one database, add its ID to the URL: `jdbc:d1://<account id>/<database id>`. The database ID is in the `uuid` column of `wrangler d1 list`.

## Settings

Add settings to the end of the URL, for example `jdbc:d1://<account id>?readOnly=true`. You can also set them on the Advanced tab of the data source.

| Setting              | Default | What it does                                                                                                                             |
|----------------------|---------|------------------------------------------------------------------------------------------------------------------------------------------|
| `password`           | none    | Your Cloudflare API token. Put it in the password field. The driver does not accept it in the URL, because IDEs save URLs as plain text. |
| `readOnly`           | `false` | Blocks statements that change data.                                                                                                      |
| `schemaCacheSeconds` | `30`    | How long the driver keeps the table list before it reads it again. `0` reads it every time.                                              |

Read-only mode is a check in the driver. For a guarantee from Cloudflare, also use a token with D1 Read permission.

## Limits

- D1 has no transactions that you can control. Each statement is saved when it runs, so rollback is not possible. A batch, or SQL that holds more than one statement, is saved completely or not at all.
- If Cloudflare does not reply, a statement that changes data can be saved or not saved. The error message tells you this.
- Whole numbers larger than 9,007,199,254,740,992 can lose their last digits when you read them, because Cloudflare sends them as JSON decimals. The database stores them exactly. To read every digit, use `cast(x as text)`.
- Cloudflare allows 1,200 API calls in 5 minutes for each Cloudflare user. This limit includes the calls from the dashboard and from your other tokens. After that, Cloudflare blocks all calls for 5 minutes (https://developers.cloudflare.com/fundamentals/api/reference/limits/). The driver reads the tables of one database in 2 calls, plus 1 more call for each 200 extra lookups in a large database. It keeps the result for 30 seconds.
- A statement can have a maximum of 100 bound values (https://developers.cloudflare.com/d1/platform/limits/). SQL that holds more than one statement cannot have bound values.
- Grid editing works only for a query that reads from one table, with no joins, grouping or unions.
- After an insert, the only generated key that the driver can return is the row ID.
- Primary keys and foreign keys have names only when the CREATE TABLE statement gives them names.
- The tree does not show virtual tables, for example full-text search tables. You can still query them.

The behaviour that the Cloudflare docs do not describe comes from live tests. The results are in `docs/research/live-tests.md`.

## Build

You need Java 17 or later.

```sh
./gradlew build
```

The build makes two files:

- The driver: `build/libs/d1-jdbc-<version>-all.jar`
- The plugin: `ide-plugin/build/libs/d1-jdbc-ide-plugin-<version>.jar`

A release build, made with `-PreleaseVersion`, sets the plugin to download the driver from the GitHub release with the same version. Any other build sets the plugin to use the driver jar in `build/libs`, so you can test the plugin before a release.

The tests use a stub server and an in-memory SQLite database. To also run the live tests, copy `.env.example` to `.env`, then add the details of a test database. The live tests make and delete their own tables.

## Licence

MIT. See `LICENSE`.
