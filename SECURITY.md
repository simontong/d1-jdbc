# Security

## Report a problem

Do not report a security problem in a public issue. Use the "Report a vulnerability" button on the Security tab of this repository. GitHub sends the report only to the maintainer.

## Your API token

- The driver sends your Cloudflare API token only to `https://api.cloudflare.com`.
- The driver does not write the token to logs or error messages.
- The driver does not accept the token in the JDBC URL, because tools save URLs as plain text.
- The token can reach every D1 database in your Cloudflare account. For read-only use, make a token with D1 Read permission.

If your token leaks, delete it in the Cloudflare dashboard, then make a new one.
